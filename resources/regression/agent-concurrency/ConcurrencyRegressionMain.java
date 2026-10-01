/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Black-box concurrency audit of an already running server; database access is read-only. */
public final class ConcurrencyRegressionMain {
    private final InitializerConfig config;
    private final Path out;
    private final ConcurrencyProbe probe;
    private final List<Identity> identities = new ArrayList<>();
    private final List<Turn> turns = new CopyOnWriteArrayList<>();
    private final List<Map<String,Object>> checks = new ArrayList<>();
    private final List<Map<String,Object>> samples = new CopyOnWriteArrayList<>();
    private final Map<String,Object> evidence = new LinkedHashMap<>();
    private final Duration timeout;
    private ScheduledExecutorService sampler;
    private volatile String phase = "preflight";
    private Map<String,Object> businessBefore;
    private boolean preflightOnly;

    private ConcurrencyRegressionMain(InitializerConfig config, Path out) {
        this.config = config; this.out = out; this.probe = new ConcurrencyProbe(config);
        this.timeout = Duration.ofSeconds(config.getInt("concurrency.turn-timeout-seconds", 300));
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> options = arguments(args);
        if (options.containsKey("help")) {
            System.out.println("run.sh [--users 4] [--output-dir DIR] [--config FILE] [--preflight] [--recheck DIR]\n"
                    + "Creates isolated ordinary users via API; chats with the live server; reads PG/Redis/MCP DB.\n"
                    + "Exit 0 = observed checks pass, 2 = failure, 3 = incomplete/unknown. Artifacts are local only.");
            return;
        }
        Path suite = Path.of("resources/regression/agent-concurrency");
        InitializerConfig config = InitializerConfig.load(Path.of(options.getOrDefault("config", suite.resolve("regression.properties").toString())));
        if (options.containsKey("recheck")) { recheck(config, Path.of(options.get("recheck"))); return; }
        int count = Integer.parseInt(options.getOrDefault("users", config.get("concurrency.users", "4")));
        if (count < 2 || count > 12) throw new IllegalArgumentException("--users must be between 2 and 12");
        if (config.getInt("concurrency.turn-timeout-seconds", 300) <= 0) throw new IllegalArgumentException("turn timeout must be positive");
        Path out = Path.of(options.getOrDefault("output-dir", suite.resolve("artifacts").resolve(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
                        + "-" + UUID.randomUUID().toString().substring(0,8)).toString())).toAbsolutePath().normalize();
        if (Files.exists(out)) try (var entries = Files.list(out)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("output directory must be empty: " + out);
        }
        Files.createDirectories(out);
        ConcurrencyRegressionMain run = new ConcurrencyRegressionMain(config, out);
        run.preflightOnly = options.containsKey("preflight");
        System.out.println("[concurrency] artifacts=" + out);
        int exit;
        try {
            run.preflight();
            if (options.containsKey("preflight")) { System.out.println("[concurrency] PREFLIGHT_OK"); return; }
            run.createIdentities(count);
            run.startSampling();
            run.scenarios();
        } catch (Exception e) {
            run.check("runner.complete", "FAIL", e.getClass().getSimpleName() + ": " + e.getMessage());
            System.err.println("[concurrency] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            run.stopUnfinished();
            run.finishAudit();
            for (Identity identity : run.identities) { identity.sse.close(); identity.http.close(); }
        }
        exit = run.checks.stream().anyMatch(c -> "FAIL".equals(c.get("status"))) ? 2
                : run.checks.stream().anyMatch(c -> Set.of("UNKNOWN","UNCOVERED").contains(c.get("status"))) ? 3 : 0;
        if (!run.preflightOnly && !run.identities.isEmpty()) {
            Path report = out.resolve("report.md");
            if (Files.exists(report)) Files.copy(report, out.resolve("runner-report.md"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            int analysisExit = ConcurrencyReportMain.analyze(out, null);
            exit = exit == 2 || analysisExit == 2 ? 2 : Math.max(exit, analysisExit);
        }
        System.out.println("[concurrency] exit=" + exit + " report=" + out.resolve("report.md"));
        if (exit != 0) System.exit(exit);
    }

    private void preflight() throws Exception {
        Map<String,Object> env = new LinkedHashMap<>();
        env.put("observedAt", Instant.now().toString());
        env.put("configurationScope", "Local YAML declaration plus runtime /agent/v1/meta; arbitrary process overrides and object identity are not introspected.");
        for (String key : List.of("server.base-url", "execution.engine-type", "agent.chat.provider", "agent.chat.model",
                "agent.memory.context-window-chars", "agent.memory.summary-enabled", "agent.memory.long-term-enabled",
                "execution.expected-vector-type", "execution.expected-storage-type")) env.put(key, config.get(key, "unknown"));
        try (RagentHttpClient admin = new RagentHttpClient(config); JdbcClient jdbc = new JdbcClient(config, "database")) {
            var login = admin.login(config.require("auth.username"), config.require("auth.password"));
            if (!"admin".equals(login.role())) throw new IllegalStateException("configured account cannot create isolated test users");
            env.put("runtimeMeta", admin.get("/agent/v1/meta"));
            env.put("databaseTime", jdbc.queryRows("SELECT current_database(), CURRENT_TIMESTAMP::text"));
            env.put("activeProfiles", jdbc.queryRows("SELECT id,name,active::text FROM t_agent_profile WHERE deleted=0 AND active=1"));
        }
        env.put("coverage", probe.coverage());
        env.put("sourceHashes", sourceHashes());
        env.put("javaVersion", System.getProperty("java.version"));
        write(out.resolve("environment.json"), env);
        businessBefore = probe.businessSnapshot();
        write(out.resolve("business-before.json"), businessBefore);
        write(out.resolve("redis-preflight.json"), probe.redisSnapshot(List.of(), List.of()));
        check("preflight", "PASS", "Authenticated Agent endpoint and configured storage inspected; service not restarted.");
    }

    private void createIdentities(int count) throws Exception {
        Path secretDir = out.resolve(".credentials");
        Files.createDirectories(secretDir);
        Files.setPosixFilePermissions(secretDir, PosixFilePermissions.fromString("rwx------"));
        try (RagentHttpClient admin = new RagentHttpClient(config); JdbcClient jdbc = new JdbcClient(config, "database")) {
            admin.login(config.require("auth.username"), config.require("auth.password"));
            Set<String> occupied = new HashSet<>();
            for (List<String> row : jdbc.queryRows("SELECT username FROM t_user")) occupied.add(row.get(0));
            for (int i=0; i<count; i++) {
                String label = "user-" + (i+1), username = newUsername(occupied), password = UUID.randomUUID().toString();
                String userId = String.valueOf(admin.postJson("/users", Map.of("username",username,"password",password,"role","user")));
                Path credential = secretDir.resolve(label + ".json");
                Files.createFile(credential, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                write(credential, Map.of("username",username,"password",password,"userId",userId));
                RagentHttpClient http = new RagentHttpClient(config);
                String actual = http.login(username,password).userId();
                if (!userId.equals(actual)) { http.close(); throw new IllegalStateException("created/logged-in identity mismatch"); }
                String marker = "RG" + UUID.randomUUID().toString().replace("-", "").substring(0,16).toUpperCase();
                identities.add(new Identity(label,username,userId,marker,http,new ConcurrencySseClient(http,config)));
                occupied.add(username);
                writeManifest();
                System.out.println("[concurrency] created " + label + " userId=" + userId);
            }
        }
        write(out.resolve("platform-before.json"), probe.platformSnapshot(userIds(),List.of(),markers()));
    }

    private void scenarios() throws Exception {
        List<Turn> seed = wave("parallel-seed", identities, u ->
                "本次会话校验标记是"+u.marker+"。请在回答首尾原样写出标记，然后按12个编号说明如何整理电脑文件，每条约40字。此轮不查询工具，也不要保存长期记忆。", u -> null, true);
        if (seed.stream().anyMatch(t -> !t.result.successful())) throw new IllegalStateException("initial parallel wave failed; stop further model calls");
        for (Turn t : seed) t.identity.conversation = t.result.conversationId();
        wave("parallel-history",identities, u -> "仅依据本会话历史，原样写出我上一轮的校验标记；再用6条建议解释上述文件整理方法如何避免误删。不要调用工具，不要猜测标记。",u -> u.conversation,true);
        auditBoundary("after-history");

        wave("parallel-tools",identities, u -> "本次咨询编号"+u.marker+"。请实际查询店内MacBook Air与MacBook Pro商品及知识库资料，比较编程、便携、散热、外接屏，约600字；首尾带咨询编号。只读查询，不下单、不改购物车、不提交售后或工单。",u -> u.conversation,true);
        checkToolCoverage("parallel-tools", Set.of("search_product","search_knowledge"));
        wave("parallel-user-tools",identities,u -> "本次咨询编号"+u.marker+"。请用查询工具查看我当前登录账号的订单和购物车，说明各有几项；没有就明确说没有。回答带咨询编号。只查询本人，不使用别人的订单号，不创建或修改任何订单、购物车、售后或工单。",u -> u.conversation,true);
        checkToolCoverage("parallel-user-tools", Set.of("query_order","query_cart"));
        auditBoundary("after-tools");

        gateScenario();
        cancellationScenario();
        isolationApiScenario();

        wave("parallel-memory-write",identities,u -> "请长期记住：我的工作电脑资产标签是"+u.marker+"，以后跨会话问到时也应准确回答。请现在调用flush_memory记忆整理工具保存，完成后原样复述标签，不查询订单或商品。",u -> u.conversation,true);
        checkToolCoverage("parallel-memory-write", Set.of("flush_memory"));
        awaitQuiet();
        auditBoundary("after-memory-write");
        wave("parallel-memory-recall",identities,u -> "我们第一次在这个新会话交谈。请只根据你已保存的关于我的长期记忆，原样告诉我工作电脑的资产标签；没有记忆就明确说不知道，不猜测、不查商品工具。",u -> null,true);
        auditBoundary("after-memory-recall");
    }

    private List<Turn> wave(String name,List<Identity> users,Function<Identity,String> question,
                            Function<Identity,String> conversation,boolean expectMarker) throws Exception {
        phase = name;
        System.out.println("[concurrency] START " + name + " users=" + users.size());
        List<Turn> batch = new ArrayList<>();
        for (Identity user : users) batch.add(start(name+"-"+user.label,user,question.apply(user),conversation.apply(user),"NORMAL",expectMarker));
        for (Turn t : batch) collect(t);
        writeManifest();
        System.out.println("[concurrency] END " + name);
        return batch;
    }

    private Turn start(String name,Identity user,String question,String conversation,String expected,boolean marker) throws Exception {
        if (question.codePointCount(0,question.length())>300) throw new IllegalArgumentException("question exceeds 300 characters");
        Turn turn = new Turn(name,user,question,expected,marker,user.sse.start(question,conversation,timeout,out.resolve("sse").resolve(name+".jsonl")));
        turns.add(turn); return turn;
    }

    private void collect(Turn turn) throws Exception {
        if (turn.result != null) return;
        turn.result = turn.live.completion().get(timeout.toSeconds()+15,TimeUnit.SECONDS);
        Map<String,Object> row = turnMap(turn);
        write(out.resolve("turns").resolve(turn.name+".json"),row);
        if (turn.result.timedOut() && turn.result.taskId()!=null) stop(turn.identity,turn.result.taskId());
        if ("NORMAL".equals(turn.expected)) {
            check(turn.name+".completed",turn.result.successful()?"PASS":"FAIL",turn.result.errors().toString());
            if (turn.expectMarker) check(turn.name+".own-marker",turn.result.answer().contains(turn.identity.marker)?"PASS":"FAIL","Own random marker must appear in the answer.");
        } else if ("CANCELLED".equals(turn.expected)) {
            check(turn.name+".cancelled",turn.result.cancelled()&&turn.result.done()&&!turn.result.timedOut()
                    &&turn.result.errors().isEmpty()&&turn.result.messageId()!=null?"PASS":"FAIL",
                    "Stop after model activity must yield a complete cancel/done payload with a persisted message, not timeout.");
        } else {
            String serialized=SimpleJson.stringify(turn.result.toMap());
            check(turn.name+".rejected",turn.result.conversationId()==null&&!turn.result.successful()&&serialized.contains("处理中")?"PASS":"FAIL","Same-user overlapping request must be rejected before creating a conversation.");
        }
        String raw=Files.readString(out.resolve("sse").resolve(turn.name+".jsonl"))
                + SimpleJson.stringify(turn.result.toMap()); // Also inspect reconstructed text: a marker can span frames.
        for(Identity other:identities) if(other!=turn.identity) check(turn.name+".no-"+other.label,
                raw.contains(other.marker)?"FAIL":"PASS","Foreign marker absent from all raw frames, including reasoning/tool blocks.");
        System.out.println("[concurrency] " + turn.name + " expected="+turn.expected+" successful="+turn.result.successful()+" tools="+turn.result.toMap().get("tools"));
    }

    private void gateScenario() throws Exception {
        phase="same-user-gate";
        Identity a=identities.get(0);
        Turn active=start("gate-active",a,longQuestion(a),a.conversation,"NORMAL",true);
        active.live.awaitMeta(Duration.ofSeconds(30));
        boolean running=!active.live.completion().isDone();
        if(!running) { check("gate.overlap-precondition","UNKNOWN","Active request finished before the competing requests."); collect(active); return; }
        Turn same=start("gate-same-conversation",a,"这是一条应被并发闸门拒绝的请求，请只回复收到。",a.conversation,"REJECTED",false);
        Turn different=start("gate-other-conversation",a,"这是同用户另一个会话的并行请求，请只回复收到。",null,"NORMAL",false);
        different.live.awaitMeta(Duration.ofSeconds(30));
        boolean overlapping = !active.live.completion().isDone();
        evidence.put("gateActiveAtNewConversationMeta", overlapping);
        check("gate.overlap-precondition",overlapping?"PASS":"UNKNOWN",
                "The original request must still be active when the new conversation receives META.");
        collect(same); collect(different);
        collect(active);
        wave("gate-recovery",List.of(a),u->"请原样回答本会话最早的校验标记。",u->u.conversation,true);
    }

    private void cancellationScenario() throws Exception {
        phase="cancel-isolation";
        Identity a=identities.get(0), b=identities.get(1);
        Turn ta=start("cancel-target",a,longQuestion(a),a.conversation,"CANCELLED",false);
        Turn tb=start("cancel-survivor",b,longQuestion(b),b.conversation,"NORMAL",true);
        ta.live.awaitMeta(Duration.ofSeconds(30)); tb.live.awaitMeta(Duration.ofSeconds(30));
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<deadline&&!ta.live.completion().isDone()&&!tb.live.completion().isDone()) {
            if(hasModelActivity(ta.live.snapshot())&&hasModelActivity(tb.live.snapshot())) break;
            Thread.sleep(100);
        }
        boolean bothActive=!ta.live.completion().isDone()&&!tb.live.completion().isDone();
        check("cancel.overlap-precondition",bothActive?"PASS":"UNKNOWN","Both streams must be alive when cancellation is submitted.");
        check("cancel.model-activity-precondition",hasModelActivity(ta.live.snapshot())&&hasModelActivity(tb.live.snapshot())?"PASS":"UNKNOWN",
                "Both streams must show model activity before cancellation, rather than only an accepted request.");
        String taskId=String.valueOf(ta.live.snapshot().get("taskId"));
        boolean denied=false;
        try { b.http.postEmpty("/agent/v1/stop?taskId="+RagentHttpClient.encodeQuery(taskId)); }
        catch(Exception e) { denied=e.getMessage()!=null&&(e.getMessage().contains("权限")||e.getMessage().contains("403")||e.getMessage().contains("任务不存在或已结束")); evidence.put("foreignStopError",e.getMessage()); }
        check("cancel.foreign-user-denied",denied?"PASS":"FAIL","User B cannot stop user A's active task.");
        if(bothActive) {
            evidence.put("redisBeforeStop",probe.redisSnapshot(userIds(),taskIds()));
            stop(a,taskId);
            evidence.put("redisAfterStop",probe.redisSnapshot(userIds(),taskIds()));
        }
        collect(ta); collect(tb);
        wave("cancel-recovery",List.of(a,b),u->"请依据本会话历史原样回答最早的校验标记，再给出一条简短的电脑文件备份建议。",u->u.conversation,true);
    }

    private void isolationApiScenario() throws Exception {
        phase="api-owner-isolation";
        Identity a=identities.get(0), b=identities.get(1);
        boolean inaccessible=false;
        try {
            Object response=b.http.get("/agent/v1/conversations/"+RagentHttpClient.encodePath(a.conversation)+"/messages");
            evidence.put("foreignConversationResponse",response);
            inaccessible=response instanceof List<?> list&&list.isEmpty();
        } catch(Exception e) { inaccessible=e.getMessage()!=null&&(e.getMessage().contains("不存在")||e.getMessage().contains("权限")||e.getMessage().contains("403")); evidence.put("foreignConversationError",e.getMessage()); }
        check("api.foreign-conversation-unreadable",inaccessible?"PASS":"FAIL","User B cannot read A's conversation through the message API.");
    }

    private static boolean hasModelActivity(Map<String,Object> state) {
        return number(state.get("thinkChars"))>0 || !String.valueOf(state.getOrDefault("answer","")).isEmpty()
                || state.get("firstTokenAt")!=null || state.get("firstModelEventAt")!=null;
    }
    private String longQuestion(Identity u) {
        return "本轮标记"+u.marker+"，回答首尾都原样写出。请写一份约2200字的个人电脑文件管理与备份指南，分20个编号，逐项说明步骤、例子和注意事项。不要调用工具，不要保存长期记忆，直接持续输出完整指南。";
    }
    private void stop(Identity user,String taskId) throws Exception {
        user.http.postEmpty("/agent/v1/stop?taskId="+RagentHttpClient.encodeQuery(taskId));
    }
    private void stopUnfinished() {
        for(Turn t:turns) if(!t.live.completion().isDone() || !Boolean.TRUE.equals(t.live.snapshot().get("done"))) {
            try { Object id=t.live.snapshot().get("taskId"); if(id!=null) stop(t.identity,String.valueOf(id)); }
            catch(Exception e) { check(t.name+".cleanup","UNKNOWN",String.valueOf(e.getMessage())); }
        }
        for(Turn t:turns) if(t.result==null) try { collect(t); } catch(Exception e) { check(t.name+".collection","UNKNOWN",String.valueOf(e.getMessage())); }
    }

    private void startSampling() {
        sampler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"concurrency-redis-probe");t.setDaemon(true);return t;});
        sampler.scheduleWithFixedDelay(()->{
            try {
                Map<String,Object> sample=new LinkedHashMap<>(probe.redisSnapshot(userIds(),taskIds()));
                sample.put("phase",phase); samples.add(sample);
                Files.writeString(out.resolve("redis-samples.jsonl"),SimpleJson.stringify(sample)+"\n",StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
            } catch(Exception e) {
                samples.add(Map.of("phase",phase,"sampledAt",Instant.now().toString(),"error",String.valueOf(e.getMessage())));
            }
        },0,Math.max(100,config.getInt("concurrency.sample-interval-millis",250)),TimeUnit.MILLISECONDS);
    }
    private void awaitQuiet() throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(config.getInt("concurrency.settle-timeout-seconds",60)).toNanos();
        int quiet=0;
        try(JdbcClient jdbc=new JdbcClient(config,"database")) {
            while(System.nanoTime()<deadline) {
                long processing=jdbc.queryLong("SELECT count(*) FROM t_agent_memory_extraction WHERE status='PROCESSING' AND user_id IN ("+literals(userIds())+")");
                Map<String,Object> redis=probe.redisSnapshot(userIds(),taskIds());
                if(processing==0&&number(redis.get("activeUserCount"))==0) quiet++; else quiet=0;
                if(quiet>=4) return;
                Thread.sleep(1000);
            }
        }
        check(phase+".settled","UNKNOWN","Background extraction or running keys did not become quiescent within the observation deadline.");
    }
    private void auditBoundary(String label) throws Exception {
        awaitQuiet();
        Map<String,Object> snapshot=probe.platformSnapshot(userIds(),conversationIds(),markers());
        write(out.resolve("platform-"+label+".json"),snapshot);
        check(label+".platform-integrity",snapshot.get("violations") instanceof List<?> list&&list.isEmpty()?"PASS":"FAIL",SimpleJson.stringify(snapshot.get("violations")));
        writeManifest();
    }

    private void finishAudit() {
        if(sampler!=null) { sampler.shutdown(); try { sampler.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
        if(!identities.isEmpty()) {
            try { phase="final-settlement"; awaitQuiet(); }
            catch(Exception e) { check("database.settlement","UNKNOWN",String.valueOf(e.getMessage())); }
            try {
                Map<String,Object> platform=probe.platformSnapshot(userIds(),conversationIds(),markers());
                write(out.resolve("platform-after.json"),platform); evidence.put("platformSummary",platform.get("summary"));
                Object violations=platform.get("violations");
                check("database.platform-integrity",violations instanceof List<?> list&&list.isEmpty()?"PASS":"FAIL",SimpleJson.stringify(violations));
                check("database.no-nonterminal-messages",platform.get("nonterminalMessages") instanceof List<?> list&&list.isEmpty()?"PASS":"FAIL",SimpleJson.stringify(platform.get("nonterminalMessages")));
            } catch(Exception e) { check("database.platform-audit","UNKNOWN",e.getClass().getSimpleName()+": "+e.getMessage()); }
            try {
                Map<String,Object> redis=probe.redisSnapshot(userIds(),taskIds());
                write(out.resolve("redis-after.json"),redis);
                check("redis.user-gates-released",number(redis.get("activeUserCount"))==0?"PASS":"FAIL","All test users' running keys must be absent after settling.");
                boolean noTaskKeys=true;
                for(Object item:SimpleJson.array(redis.get("tasks"))) {
                    Map<String,Object> task=SimpleJson.object(item);
                    for(String kind:List.of("owner","cancel")) if(Boolean.TRUE.equals(SimpleJson.object(task.get(kind)).get("exists"))) noTaskKeys=false;
                }
                check("redis.task-keys-released",noTaskKeys?"PASS":"FAIL","All test task owner/cancel keys must be absent.");
            } catch(Exception e) { check("redis.final-audit","UNKNOWN",String.valueOf(e.getMessage())); }
            try {
                Map<String,Object> after=probe.businessSnapshot(userIds()); write(out.resolve("business-after.json"),after);
                evidence.put("businessAfter",after);
                evidence.put("businessBefore",businessBefore);
                Map<String,Object> business=SimpleJson.object(after.get("business"));
                Object beforeTables=businessBefore==null?null:SimpleJson.object(businessBefore.get("business")).get("tables");
                check("business.no-net-change",business.get("tables").equals(beforeTables)?"PASS":"UNKNOWN","Compare whole-table row counts and fingerprints; external changes cannot be attributed to this test.");
                for(Object item:SimpleJson.array(business.get("testUserCounts"))) {
                    Map<String,Object> counts=SimpleJson.object(item);
                    boolean empty=counts.entrySet().stream().filter(e->!"userId".equals(e.getKey())).allMatch(e->number(e.getValue())==0);
                    check("business.empty-test-user-"+counts.get("userId"),empty?"PASS":"FAIL","Read-only new accounts must have no orders/cart/after-sales/tickets.");
                }
                Map<String,Object> vector=SimpleJson.object(after.get("pgvector"));
                if(vector.containsKey("t_knowledge_vector")) check("pgvector.no-net-change",vector.get("t_knowledge_vector").equals(SimpleJson.object(businessBefore.get("pgvector")).get("t_knowledge_vector"))?"PASS":"UNKNOWN","No net change to vector table between snapshots.");
            } catch(Exception e) { check("business.final-audit","UNKNOWN",String.valueOf(e.getMessage())); }
            try { verifyTurnPersistence(); } catch(Exception e) { check("database.turn-persistence","UNKNOWN",String.valueOf(e.getMessage())); }
            int max=samples.stream().mapToInt(s->number(s.get("activeUserCount"))).max().orElse(0);
            evidence.put("maxSimultaneousRedisRunningUsers",max);
            check("parallel.actual-server-overlap",max>=2?"PASS":"UNKNOWN","Atomic Redis snapshots: max simultaneous test users="+max);
            for(String name:List.of("parallel-seed","parallel-history","parallel-tools","parallel-user-tools","parallel-memory-write","parallel-memory-recall")) {
                int phaseMax=samples.stream().filter(s->name.equals(s.get("phase"))).mapToInt(s->number(s.get("activeUserCount"))).max().orElse(0);
                check(name+".server-overlap",phaseMax>=2?"PASS":"UNKNOWN","Atomic Redis active users="+phaseMax);
            }
            long errors=samples.stream().filter(s->s.containsKey("error")).count();
            evidence.put("redisSampleCount",samples.size()); evidence.put("redisSampleErrors",errors);
            if(errors>0) check("redis.sampling-completeness","UNKNOWN",errors+" sampling failures");
        }
        try { writeManifest(); write(out.resolve("evidence.json"),evidence); write(out.resolve("checks.json"),checks); report(); }
        catch(Exception e) { throw new IllegalStateException("Failed to preserve final audit artifacts",e); }
    }

    private void verifyTurnPersistence() throws Exception {
        try(JdbcClient jdbc=new JdbcClient(config,"database")) {
            Set<String> taskIds=new HashSet<>(),messageIds=new HashSet<>();
            for(Turn t:turns) {
                if(t.result==null) continue;
                if(t.result.taskId()!=null) check(t.name+".unique-task-id",taskIds.add(t.result.taskId())?"PASS":"FAIL","Distinct accepted requests have distinct task IDs.");
                if(t.result.messageId()!=null) {
                    check(t.name+".unique-message-id",messageIds.add(t.result.messageId())?"PASS":"FAIL","Distinct replies have distinct persisted IDs.");
                    var rows=jdbc.queryRows("SELECT user_id,conversation_id,role,message_status,COALESCE(content,'') FROM t_agent_message WHERE id="+JdbcClient.literal(t.result.messageId()));
                    boolean owner=rows.size()==1&&rows.get(0).get(0).equals(t.identity.userId)&&rows.get(0).get(1).equals(t.result.conversationId())&&"assistant".equalsIgnoreCase(rows.get(0).get(2));
                    check(t.name+".database-owner",owner?"PASS":"FAIL","finish.messageId must resolve to the same user and conversation.");
                    if(owner) {
                        String expectedStatus="CANCELLED".equals(t.expected)?"INTERRUPTED":"NORMAL";
                        check(t.name+".database-status",expectedStatus.equals(rows.get(0).get(3))?"PASS":"FAIL","Expected persisted status="+expectedStatus);
                        if("NORMAL".equals(t.expected)) check(t.name+".database-answer",rows.get(0).get(4).equals(t.result.answer())?"PASS":"FAIL","SSE accumulated answer must exactly equal the persisted assistant content.");
                    }
                }
                if("REJECTED".equals(t.expected)) {
                    long persisted=jdbc.queryLong("SELECT count(*) FROM t_agent_message WHERE user_id="+JdbcClient.literal(t.identity.userId)+" AND content="+JdbcClient.literal(t.question));
                    check(t.name+".no-persisted-input",persisted==0?"PASS":"FAIL","Rejected same-user requests must not append user messages.");
                }
            }
        }
    }

    private void checkToolCoverage(String prefix,Set<String> expected) {
        Set<String> observed=new HashSet<>(); Map<String,Set<String>> successfulUsers=new LinkedHashMap<>();
        for(Turn turn:turns) if(turn.name.startsWith(prefix+"-")&&turn.result!=null) {
            Object tools=turn.result.toMap().get("tools");
            if(tools instanceof Iterable<?> values) for(Object value:values) observed.add(String.valueOf(value));
            Object states=turn.result.toMap().get("toolStates");
            if(states instanceof Map<?,?> values) for(Object value:values.values()) {
                Map<String,Object> tool=SimpleJson.object(value);
                if("done".equals(tool.get("status"))) successfulUsers.computeIfAbsent(String.valueOf(tool.get("name")),k->new HashSet<>()).add(turn.identity.userId);
            }
        }
        evidence.put(prefix+".observedTools",observed);
        for(String tool:expected) check(prefix+".tool-"+tool,successfulUsers.getOrDefault(tool,Set.of()).size()>=2?"PASS":"UNCOVERED","Requires done tool blocks from at least two distinct users. Observed="+observed+", successfulUsers="+successfulUsers.getOrDefault(tool,Set.of()).size());
    }
    private void check(String name,String status,String detail) {
        checks.add(Map.of("name",name,"status",status,"detail",detail==null?"":detail));
    }
    private void writeManifest() throws Exception {
        Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("updatedAt",Instant.now().toString());
        manifest.put("identities",identities.stream().map(u->Map.of("label",u.label,"username",u.username,"userId",u.userId,"marker",u.marker)).toList());
        manifest.put("turns",turns.stream().map(this::turnMap).toList());
        write(out.resolve("run.json"),manifest);
    }
    private Map<String,Object> turnMap(Turn t) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("name",t.name);m.put("userId",t.identity.userId);m.put("label",t.identity.label);
        m.put("expected",t.expected);m.put("question",t.question);m.put("result",t.result==null?t.live.snapshot():t.result.toMap());return m;
    }
    private void report() throws Exception {
        if(preflightOnly) {
            Files.writeString(out.resolve("report.md"),"# Ragent 并发回归预检\n\n仅检查服务和数据库连通性，没有执行并发请求，不能据此判断是否支持并行。详见 environment.json 和 checks.json。\n",StandardCharsets.UTF_8);
            return;
        }
        long fail=checks.stream().filter(c->"FAIL".equals(c.get("status"))).count();
        long unknown=checks.stream().filter(c->Set.of("UNKNOWN","UNCOVERED").contains(c.get("status"))).count();
        String verdict=fail>0?"FAIL：存在失败检查，不能据此宣称并发链路全部正确。":unknown>0?"INCOMPLETE：部分观测不足，需要结合失败/未知项判断。":"PASS：本次覆盖路径支持多用户并行，检查未发现串用户。";
        StringBuilder md=new StringBuilder("# Ragent 真服务并发回归报告\n\n").append("生成时间：").append(Instant.now()).append("\n\n**").append(verdict).append("**\n\n")
                .append("- 服务：").append(config.require("server.base-url")).append("\n- 独立测试账号：").append(identities.size())
                .append("\n- 请求数（含预期拒绝）：").append(turns.size()).append("\n- Redis 原子快照最大同时运行用户数：").append(evidence.getOrDefault("maxSimultaneousRedisRunningUsers",0))
                .append("\n- FAIL：").append(fail).append("；UNKNOWN：").append(unknown).append("\n\n")
                .append("## 判定依据\n\n并行看多个用户运行位在同一 Redis 原子快照中共存；SSE 原始帧保存独立时间线。隔离看随机用户标记、任务/消息身份、会话状态、长期记忆、工具结果及取消收尾。请求同时发出、模型自称调用了工具都不作为独立通过证据。\n\n")
                .append("## 请求结果\n\n| 请求 | 预期 | 实际成功 | 会话 | 工具 |\n|---|---|---|---|---|\n");
        for(Turn t:turns) md.append('|').append(t.name).append('|').append(t.expected).append('|').append(t.result!=null&&t.result.successful())
                .append('|').append(t.result==null?"":t.result.conversationId()).append('|').append(t.result==null?"":escape(String.valueOf(t.result.toMap().get("tools")))).append("|\n");
        md.append("\n## 断言与覆盖\n\n| 检查 | 结果 | 证据/说明 |\n|---|---|---|\n");
        for(Map<String,Object> c:checks) md.append('|').append(c.get("name")).append('|').append(c.get("status")).append('|').append(escape(String.valueOf(c.get("detail")))).append("|\n");
        md.append("\n## 数据库证据\n\n- [环境与覆盖](environment.json)\n- [平台库最终快照](platform-after.json)\n- [Redis 采样](redis-samples.jsonl)及[最终状态](redis-after.json)\n- [业务/向量库基线](business-before.json)与[结束快照](business-after.json)\n- [完整请求索引](run.json)、[机器断言](checks.json)、[补充证据](evidence.json)\n\n")
                .append("## 结论边界\n\n本次是当前已启动进程、当前模型、当前工具接入方式和有限并发度的实测，不是 ReActAgent 所有功能线程安全的证明。黑盒接口不暴露 Agent 对象 identity；复用方式来自源码，未验证所有运行时覆盖。未注入原生动态工具组或 ToolEmitter，未执行交易写工具/人工确认；未强制造超长上下文触发压缩，未做多节点、进程重启或极限容量测试。工具未实际执行的项标为 UNCOVERED，观测失败标为 UNKNOWN。业务库整体指纹变化可能来自其他客户端，不能单凭变化归因本次回归。\n\n")
                .append("本回归通过 API 创建普通账号与聊天数据，数据库探针只读；账号和记录保留复查。凭据只在 .credentials/（目录 700，文件 600），产物目录被 .gitignore 忽略。\n");
        Files.writeString(out.resolve("report.md"),md,StandardCharsets.UTF_8);
    }

    private List<String> userIds(){return identities.stream().map(u->u.userId).toList();}
    private List<String> taskIds(){return turns.stream().map(t->t.live.snapshot().get("taskId")).filter(java.util.Objects::nonNull).map(String::valueOf).distinct().toList();}
    private List<String> conversationIds(){return turns.stream().map(t->t.live.snapshot().get("conversationId")).filter(java.util.Objects::nonNull).map(String::valueOf).distinct().toList();}
    private Map<String,String> markers(){Map<String,String> m=new LinkedHashMap<>();identities.forEach(u->m.put(u.userId,u.marker));return m;}
    private static int number(Object value){return value instanceof Number n?n.intValue():0;}
    private static String literals(List<String> values){return values.isEmpty()?"NULL":values.stream().map(JdbcClient::literal).collect(java.util.stream.Collectors.joining(","));}
    private static String escape(String s){return s.replace("|","\\|").replace("\n"," ").replace("\r"," ");}
    static void write(Path file,Object value) throws Exception {Files.createDirectories(file.getParent());Files.writeString(file,SimpleJson.stringify(value)+"\n",StandardCharsets.UTF_8);}
    private static Map<String,String> arguments(String[] args){
        Map<String,String> result=new LinkedHashMap<>();Set<String> flags=Set.of("help","preflight");Set<String> values=Set.of("users","output-dir","config","recheck");
        for(int i=0;i<args.length;i++){String key=args[i].replaceFirst("^--","");if(flags.contains(key))result.put(key,"true");else if(values.contains(key)&&i+1<args.length)result.put(key,args[++i]);else throw new IllegalArgumentException("Unknown/missing CLI argument: "+args[i]);}return result;
    }
    private static String newUsername(Set<String> occupied) {
        for(String surname:List.of("smith","johnson","brown","wilson","taylor","anderson","thomas","moore","martin","clark"))
            for(String given:List.of("james","emma","oliver","emily","henry","alice","jack","grace","daniel","lucy","michael","sarah","david","anna","william","sophie","thomas","chloe","george","charlotte"))
                if(!occupied.contains(given+surname))return given+surname;
        throw new IllegalStateException("Available ordinary-name accounts exhausted");
    }
    private static Map<String,Object> sourceHashes() throws Exception {
        Map<String,Object> hashes=new LinkedHashMap<>();
        for(String name:List.of("pom.xml","agent/src/main/java/com/nageoffer/ai/ragent/agent/config/ReActAgentProvider.java",
                "agent/src/main/java/com/nageoffer/ai/ragent/agent/service/impl/AgentChatServiceImpl.java")) {
            Path file=Path.of(name);if(Files.exists(file))hashes.put(name,java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return hashes;
    }
    private static void recheck(InitializerConfig config,Path out) throws Exception {
        Map<String,Object> run=SimpleJson.object(SimpleJson.parse(Files.readString(out.resolve("run.json"))));
        List<String> users=new ArrayList<>(),conversations=new ArrayList<>(),tasks=new ArrayList<>();Map<String,String> markers=new LinkedHashMap<>();
        for(Object item:SimpleJson.array(run.get("identities"))){Map<String,Object> u=SimpleJson.object(item);String id=String.valueOf(u.get("userId"));users.add(id);markers.put(id,String.valueOf(u.get("marker")));}
        for(Object item:SimpleJson.array(run.get("turns"))){Map<String,Object> r=SimpleJson.object(SimpleJson.object(item).get("result"));if(r.get("conversationId")!=null)conversations.add(String.valueOf(r.get("conversationId")));if(r.get("taskId")!=null)tasks.add(String.valueOf(r.get("taskId")));}
        try(var entries=Files.list(out)) {
            for(Path diagnostic:entries.filter(p->p.getFileName().toString().startsWith("gate-diagnostic-")).toList()) {
                Path file=diagnostic.resolve("diagnostic.json");if(!Files.isRegularFile(file))continue;
                Map<String,Object> data=SimpleJson.object(SimpleJson.parse(Files.readString(file)));
                for(String key:List.of("targetFinalSnapshot","survivorFinalSnapshot")) if(data.get(key) instanceof Map<?,?>) {
                    Map<String,Object> r=SimpleJson.object(data.get(key));
                    if(r.get("conversationId")!=null)conversations.add(String.valueOf(r.get("conversationId")));
                    if(r.get("taskId")!=null)tasks.add(String.valueOf(r.get("taskId")));
                }
            }
        }
        ConcurrencyProbe probe=new ConcurrencyProbe(config);Path target=out.resolve("recheck-"+System.currentTimeMillis());
        write(target.resolve("platform.json"),probe.platformSnapshot(users,conversations,markers));write(target.resolve("redis.json"),probe.redisSnapshot(users,tasks));write(target.resolve("business.json"),probe.businessSnapshot(users));
        System.out.println("[concurrency] read-only recheck="+target);
    }
    private static final class Identity {
        final String label,username,userId,marker;final RagentHttpClient http;final ConcurrencySseClient sse;String conversation;
        Identity(String l,String n,String id,String m,RagentHttpClient h,ConcurrencySseClient s){label=l;username=n;userId=id;marker=m;http=h;sse=s;}
    }
    private static final class Turn {
        final String name,question,expected;final Identity identity;final boolean expectMarker;final ConcurrencySseClient.LiveTurn live;ConcurrencySseClient.Result result;
        Turn(String n,Identity i,String q,String e,boolean m,ConcurrencySseClient.LiveTurn l){name=n;identity=i;question=q;expected=e;expectMarker=m;live=l;}
    }
}
