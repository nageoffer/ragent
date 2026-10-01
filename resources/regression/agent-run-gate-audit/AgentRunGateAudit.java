import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Run focused Java tests and watchdog checks against a disposable loopback Redis
 */
public class AgentRunGateAudit {
    private static final String TESTS = "AgentRunGateTest,AgentRunHandleTest,AgentChatServiceImplTest,"
            + "AgentConversationServiceImplTest,StreamTaskManagerTest";

    public static void main(String[] args) throws Exception {
        boolean skipTests = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--skip-tests" -> skipTests = true;
                case "--help", "-h" -> {
                    System.out.println("Usage: run.sh [--skip-tests]");
                    System.out.println("Requires Java 17, Maven, redis-server and cached Maven dependencies");
                    System.out.println("--skip-tests reuses the current focused Maven test classpath");
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        Path repo = Path.of(args[0]);
        Path suite = repo.resolve("resources/regression/agent-run-gate-audit");
        Path output = Files.createTempDirectory(Files.createDirectories(suite.resolve("artifacts")), "run-");
        System.out.println("Audit artifacts: " + output);
        if (!skipTests) {
            run(List.of("mvn", "-o", "-pl", "agent", "-am", "-Dtest=" + TESTS,
                    "-Dsurefire.failIfNoSpecifiedTests=false", "test"), repo, output.resolve("baseline.log"), 0);
        }

        Path report = repo.resolve("agent/target/surefire-reports/"
                + "TEST-com.nageoffer.ai.ragent.agent.service.handler.AgentRunGateTest.xml");
        String classpath = readClasspath(report);
        String mockito = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .filter(path -> path.contains("mockito-core-") && path.endsWith(".jar"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Mockito agent missing from test classpath"));
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        run(List.of(javaBin.resolve("javac").toString(), "-encoding", "UTF-8", "-cp", classpath,
                "-d", output.toString(), suite.resolve("AgentRunGateProbe.java").toString()),
                repo, output.resolve("compile.log"), 0);

        int port;
        try (ServerSocket candidate = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = candidate.getLocalPort();
        }
        Path redisLog = output.resolve("redis.log");
        Process server = start(List.of("redis-server", "--bind", "127.0.0.1", "--port", String.valueOf(port),
                "--save", "", "--appendonly", "no", "--daemonize", "no"), output, redisLog);
        Thread cleanup = new Thread(() -> stop(server));
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            awaitRedis(server, port, redisLog);
            Path probeLog = output.resolve("probe.log");
            run(List.of(javaBin.resolve("java").toString(), "-javaagent:" + mockito, "-cp",
                    output + File.pathSeparator + classpath, "AgentRunGateProbe", String.valueOf(port)),
                    repo, probeLog, 60);
            try (var lines = Files.lines(probeLog)) {
                lines.filter(line -> line.startsWith("AUDIT")).forEach(System.out::println);
            }
            System.out.println("AgentRunGate checks completed");
        } finally {
            stop(server);
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
    }

    private static String readClasspath(Path report) throws Exception {
        var properties = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(report.toFile()).getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if ("java.class.path".equals(property.getAttribute("name"))) {
                return property.getAttribute("value");
            }
        }
        throw new IllegalStateException("Test classpath missing from " + report);
    }

    private static Process start(List<String> command, Path directory, Path log) throws IOException {
        return new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    private static void run(List<String> command, Path directory, Path log, int timeoutSeconds) throws Exception {
        Process process = start(command, directory, log);
        Thread cleanup = new Thread(() -> stop(process));
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            if (timeoutSeconds == 0) {
                process.waitFor();
            } else if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(command.get(0) + " timed out; see " + log);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(command.get(0) + " exited with " + process.exitValue() + "; see " + log);
            }
        } finally {
            stop(process);
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
    }

    private static void awaitRedis(Process server, int port, Path log) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!server.isAlive()) {
                throw new IllegalStateException("Temporary Redis exited; see " + log);
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (IOException notReady) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("Temporary Redis did not become ready; see " + log);
    }

    private static void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
