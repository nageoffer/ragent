/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Follow-up control: same active user, two Accept headers, another concurrently active user. */
public final class ConcurrencyGateProbeMain {
    public static void main(String[] args) throws Exception {
        if(args.length<1||args.length>2) throw new IllegalArgumentException("ConcurrencyGateProbeMain RUN_DIR [CONFIG]");
        Path run=Path.of(args[0]), output=run.resolve("gate-diagnostic-"+System.currentTimeMillis());
        InitializerConfig config=InitializerConfig.load(Path.of(args.length==2?args[1]:"resources/regression/agent-concurrency/regression.properties"));
        Map<String,Object> manifest=SimpleJson.object(SimpleJson.parse(Files.readString(run.resolve("run.json"))));
        List<Object> identities=SimpleJson.array(manifest.get("identities"));
        Map<String,Object> a=SimpleJson.object(identities.get(0)),b=SimpleJson.object(identities.get(1));
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("startedAt",Instant.now().toString());report.put("purpose","Compare expected same-user gate rejection under strict SSE vs mixed Accept, while another user runs.");
        List<Map<String,Object>> responses=new ArrayList<>();report.put("rejections",responses);
        ConcurrencySseClient.LiveTurn ta=null,tb=null;
        try(RagentHttpClient ha=new RagentHttpClient(config);RagentHttpClient hb=new RagentHttpClient(config);
            ConcurrencySseClient ca=new ConcurrencySseClient(ha,config);ConcurrencySseClient cb=new ConcurrencySseClient(hb,config)) {
            login(ha,run,a);login(hb,run,b);
            try {
                ta=ca.start(question(a),null,Duration.ofSeconds(120),output.resolve("active-a.jsonl"));
                Map<String,Object> ma=ta.awaitMeta(Duration.ofSeconds(20));
                tb=cb.start(question(b),null,Duration.ofSeconds(120),output.resolve("active-b.jsonl"));
                tb.awaitMeta(Duration.ofSeconds(20));
                report.put("redisDuring",new ConcurrencyProbe(config).redisSnapshot(
                        List.of(String.valueOf(a.get("userId")),String.valueOf(b.get("userId"))),
                        List.of(String.valueOf(ma.get("taskId")),String.valueOf(tb.snapshot().get("taskId")))));
                HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                for(String accept:List.of("text/event-stream","text/event-stream, application/json")) {
                    for(boolean existing:List.of(true,false)) {
                        Map<String,Object> response=new LinkedHashMap<>();
                        response.put("accept",accept);response.put("sameConversation",existing);
                        response.put("activeABeforeRequest",!ta.completion().isDone());response.put("requestAt",Instant.now().toString());
                        String uri=ha.baseUrl()+"/agent/v1/chat?question="+RagentHttpClient.encodeQuery("并发闸门对照请求，请只回答收到。")
                                +(existing?"&conversationId="+RagentHttpClient.encodeQuery(String.valueOf(ma.get("conversationId"))):"");
                        try {
                            var pending=client.sendAsync(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10))
                                    .header("Authorization",ha.authorization()).header("Accept",accept).GET().build(),HttpResponse.BodyHandlers.ofString());
                            HttpResponse<String> r;
                            try { r=pending.get(12,TimeUnit.SECONDS); } finally { pending.cancel(true); }
                            response.put("httpStatus",r.statusCode());response.put("contentType",r.headers().firstValue("content-type").orElse(""));response.put("body",r.body());
                        } catch(Exception e) { response.put("error",e.getClass().getSimpleName()+": "+e.getMessage()); }
                        response.put("finishedAt",Instant.now().toString());response.put("activeAAfterRequest",!ta.completion().isDone());
                        responses.add(response);ConcurrencyRegressionMain.write(output.resolve("diagnostic.json"),report);
                    }
                }
                long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
                while(ta.snapshot().get("firstTokenAt")==null&&!ta.completion().isDone()&&System.nanoTime()<deadline)Thread.sleep(50);
                report.put("targetHadModelOutput",ta.snapshot().get("firstTokenAt")!=null);
                if(!ta.completion().isDone())ha.postEmpty("/agent/v1/stop?taskId="+RagentHttpClient.encodeQuery(String.valueOf(ma.get("taskId"))));
                report.put("targetResult",ta.completion().get(130,TimeUnit.SECONDS).toMap());
                report.put("survivorResult",tb.completion().get(130,TimeUnit.SECONDS).toMap());
            } finally {
                cleanup(ha,ta);cleanup(hb,tb);
                report.put("finishedAt",Instant.now().toString());
                if(ta!=null)report.put("targetFinalSnapshot",ta.snapshot());
                if(tb!=null)report.put("survivorFinalSnapshot",tb.snapshot());
                ConcurrencyRegressionMain.write(output.resolve("diagnostic.json"),report);
            }
        }
        System.out.println("[concurrency] gate-diagnostic="+output);
        for(Map<String,Object> r:responses)System.out.println("[concurrency] accept="+r.get("accept")+" sameConversation="+r.get("sameConversation")+" status="+r.get("httpStatus")+" body="+r.get("body"));
    }
    private static void login(RagentHttpClient http,Path run,Map<String,Object> identity) throws Exception {
        Map<String,Object> credential=SimpleJson.object(SimpleJson.parse(Files.readString(run.resolve(".credentials").resolve(identity.get("label")+".json"))));
        var session=http.login(String.valueOf(credential.get("username")),String.valueOf(credential.get("password")));
        if(!String.valueOf(identity.get("userId")).equals(session.userId()))throw new IllegalStateException("identity mismatch");
    }
    private static String question(Map<String,Object> identity) {
        return "回答先写标记"+identity.get("marker")+"。请分25个编号写一份2200字的Mac文件管理与备份指南，每条包含步骤和例子。不要调用工具，不保存长期记忆，持续输出完整指南。";
    }
    private static void cleanup(RagentHttpClient http,ConcurrencySseClient.LiveTurn turn) {
        if(turn==null||Boolean.TRUE.equals(turn.snapshot().get("done")))return;
        try{Object task=turn.snapshot().get("taskId");if(task!=null)http.postEmpty("/agent/v1/stop?taskId="+RagentHttpClient.encodeQuery(String.valueOf(task)));}
        catch(Exception ignored){ /* final Redis recheck remains authoritative */ }
    }
}
