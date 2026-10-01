import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.*;

/** Real Redis regression for conversation exclusion, user permits and watchdog recovery. */
public class AgentRunGateProbe {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        RedissonClient first = client(Integer.parseInt(args[0]));
        RedissonClient second = client(Integer.parseInt(args[0]));
        List<Runnable> releases = new ArrayList<>();
        try {
            AgentProperties properties = new AgentProperties();
            AgentRunGate a = new AgentRunGate(first, properties);
            AgentRunGate b = new AgentRunGate(second, properties);
            first.getBucket("ragent:agent:running:u").set("legacy|conversation", Duration.ofMinutes(30));
            for (int i = 0; i < 5; i++) {
                releases.add((i % 2 == 0 ? a : b).acquire("u", String.valueOf(100 + i), "c" + i));
            }
            check("five conversations share permits across clients, old bucket ignored", true);
            rejected("same conversation cannot reenter on reused physical thread", () -> a.acquire("u", "200", "c0"));
            rejected("sixth conversation rejected across clients", () -> b.acquire("u", "201", "c5"));
            waitFor(() -> !second.getLock("ragent:agent:run-lock:u:c5").isLocked());
            check("quota rejection releases speculative conversation lock", true);
            Runnable other = a.acquire("other-user", "202", "c0");
            check("user identity isolates identical conversation ids", true);
            other.run();

            // More than two watchdog leases: active operations must still hold their locks.
            Thread.sleep(2200);
            check("watchdog renews conversation and permit while running",
                    second.getLock("ragent:agent:run-lock:u:c0").isLocked()
                    && second.getLock("ragent:agent:run-permit:u:0").isLocked());
            Runnable released = releases.remove(0);
            Thread callback = new Thread(released);
            callback.start();
            callback.join();
            released.run();
            waitFor(() -> !second.getLock("ragent:agent:run-lock:u:c0").isLocked());
            Runnable replacement = a.acquire("u", "203", "c0");
            check("cross-thread repeated release returns a permit", true);
            released.run();
            Thread.sleep(1200);
            check("late prior release preserves next run's locks and renewal",
                    first.getLock("ragent:agent:run-lock:u:c0").isHeldByThread(203)
                    && first.getLock("ragent:agent:run-permit:u:0").isHeldByThread(203));
            replacement.run();
            for (Runnable release : releases) release.run();
            releases.clear();
            waitFor(() -> !second.getLock("ragent:agent:run-permit:u:0").isLocked()
                    && !second.getLock("ragent:agent:run-permit:u:4").isLocked());

            Runnable running = a.acquire("u", "204", "read-status");
            check("running conversation is detected across clients", b.isRunning("u", "read-status"));
            running.run();
            waitFor(() -> !b.isRunning("u", "read-status"));

            ExecutorService pool = Executors.newFixedThreadPool(10);
            try {
                CyclicBarrier barrier = new CyclicBarrier(10);
                List<Future<Runnable>> attempts = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    final int index = i;
                    attempts.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            return (index % 2 == 0 ? a : b).acquire("parallel-user",
                                    String.valueOf(1000 + index), "parallel-" + index);
                        } catch (ClientException full) {
                            return null;
                        }
                    }));
                }
                List<Runnable> accepted = new ArrayList<>();
                for (Future<Runnable> attempt : attempts) {
                    Runnable release = attempt.get(5, TimeUnit.SECONDS);
                    if (release != null) accepted.add(release);
                }
                check("ten concurrent cross-client attempts acquire exactly five permits", accepted.size() == 5);
                accepted.forEach(Runnable::run);
            } finally {
                pool.shutdownNow();
            }

            properties.setMaxConcurrentRunsPerUser(1);
            Runnable single = a.acquire("configured-user", "205", "one");
            rejected("configured limit one is enforced", () -> b.acquire("configured-user", "206", "two"));
            single.run();
            a.acquire("crash-user", "207", "orphan");
            first.shutdown();
            waitFor(() -> !second.getLock("ragent:agent:run-lock:crash-user:orphan").isLocked()
                    && !second.getLock("ragent:agent:run-permit:crash-user:0").isLocked());
            Runnable recovered = b.acquire("crash-user", "208", "orphan");
            check("stopping owning client reclaims both locks within watchdog lease", true);
            recovered.run();
            System.out.println("AUDIT " + assertions + " real-Redis assertions passed");
        } finally {
            for (Runnable release : releases) release.run();
            if (!first.isShutdown()) first.shutdown();
            second.shutdown();
        }
    }

    private static RedissonClient client(int port) {
        Config config = new Config().setLockWatchdogTimeout(900);
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
                .setRetryAttempts(0).setTimeout(1000);
        return Redisson.create(config);
    }

    private static void rejected(String name, Runnable action) {
        try {
            action.run();
            throw new AssertionError(name + " unexpectedly acquired");
        } catch (ClientException expected) {
            check(name, true);
        }
    }

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        assertions++;
        System.out.println("AUDIT PASS " + name);
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Redis lock did not settle");
            Thread.sleep(20);
        }
    }
}
