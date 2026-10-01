/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline artifact analysis: no HTTP, database, Redis, or model calls. */
public final class ConcurrencyReportMain {
    private final Path runDir, snapshotDir;
    private final Map<String,String> markers = new LinkedHashMap<>();
    private final List<Turn> turns = new ArrayList<>();
    private final List<Map<String,Object>> checks = new ArrayList<>(), corrections = new ArrayList<>(), diagnostics = new ArrayList<>();
    private final Map<String,Object> analysis = new LinkedHashMap<>();
    private Map<String,Object> platform = Map.of(), tables = Map.of(), evidence = Map.of(), redis = Map.of(), business = Map.of();
    private List<Map<String,Object>> originalChecks = List.of();
    private int supplementaryRejected;

    private ConcurrencyReportMain(Path runDir, Path snapshotDir) {
        this.runDir = runDir.toAbsolutePath().normalize();
        this.snapshotDir = snapshotDir == null ? latestSnapshot(this.runDir) : snapshotDir.toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        Map<String,String> options = new LinkedHashMap<>();
        for (int i=0; i<args.length; i+=2) {
            if (i+1>=args.length || !Set.of("--run-dir","--snapshot-dir").contains(args[i]))
                throw new IllegalArgumentException("Usage: --run-dir DIR [--snapshot-dir RECHECK_DIR] | --self-test");
            options.put(args[i],args[i+1]);
        }
        if (!options.containsKey("--run-dir")) throw new IllegalArgumentException("--run-dir is required");
        int exit = analyze(Path.of(options.get("--run-dir")), options.containsKey("--snapshot-dir") ? Path.of(options.get("--snapshot-dir")) : null);
        if (exit != 0) System.exit(exit);
    }

    /** Callable by the runner after final audit and resource cleanup. Original evidence is never rewritten. */
    static int analyze(Path runDir, Path snapshotDir) throws IOException {
        ConcurrencyReportMain report = new ConcurrencyReportMain(runDir,snapshotDir);
        report.inspect();
        report.save();
        System.out.println("[concurrency-analysis] parallel="+report.analysis.get("parallelConclusion")
                +" isolation="+report.analysis.get("isolationConclusion")+" overall="+report.analysis.get("overallStatus")
                +" report="+report.runDir.resolve("report.md"));
        return "FAIL".equals(report.analysis.get("overallStatus")) ? 2 : "PASS".equals(report.analysis.get("overallStatus")) ? 0 : 3;
    }

    private void inspect() throws IOException {
        Map<String,Object> run = objectFile(runDir.resolve("run.json"));
        check("run.finalized","coverage",Files.isRegularFile(runDir.resolve("checks.json"))&&Files.isRegularFile(runDir.resolve("evidence.json"))?"PASS":"UNKNOWN",
                "Final checks/evidence files must exist; a live run.json is not final evidence.");
        for (Map<String,Object> user:objects(run.get("identities"))) markers.put(text(user,"userId"),text(user,"marker"));
        check("identities.distinct","integrity",markers.size()>=2&&new HashSet<>(markers.values()).size()==markers.size()?"PASS":"FAIL","Distinct users and random markers="+markers.size());
        evidence=optional(runDir.resolve("evidence.json"));
        if(Files.isRegularFile(runDir.resolve("checks.json"))) originalChecks=objects(read(runDir.resolve("checks.json")));
        for(Map<String,Object> row:objects(run.get("turns"))) turns.add(new Turn(row));
        loadDiagnostics();
        for(Turn turn:turns) { readFrames(turn); auditTurn(turn); }
        boolean recheck=!snapshotDir.equals(runDir);
        platform=optional(snapshotDir.resolve(recheck?"platform.json":"platform-after.json"));
        redis=optional(snapshotDir.resolve(recheck?"redis.json":"redis-after.json"));
        business=optional(snapshotDir.resolve(recheck?"business.json":"business-after.json"));
        tables=map(platform.get("tables"));
        check("snapshot.platform","coverage",platform.isEmpty()?"UNCOVERED":"PASS","Selected snapshot="+snapshotDir);
        auditPersistence();
        auditBoundaries();
        auditMemoryAndTools();
        auditBusiness();
        auditParallelAndRedis();
        auditGateAndCancel();
        retainOriginalChecks();
        analysis.put("generatedAt",Instant.now().toString()); analysis.put("runDirectory",runDir.toString());
        analysis.put("snapshotDirectory",snapshotDir.toString()); analysis.put("snapshotAt",platform.get("sampledAt"));
        analysis.put("identities",run.get("identities")); analysis.put("manifestUpdatedAt",run.get("updatedAt"));
        analysis.put("httpChatRequests",turns.size()+supplementaryRejected); analysis.put("recordedTurns",turns.size());
        analysis.put("normalRequests",turns.stream().filter(t->t.expected.equals("NORMAL")).count());
        analysis.put("cancelRequests",turns.stream().filter(t->t.expected.equals("CANCELLED")).count());
        analysis.put("rejectedRequests",turns.stream().filter(t->t.expected.equals("REJECTED")).count()+supplementaryRejected);
        analysis.put("turns",turns.stream().map(Turn::summary).toList()); analysis.put("collectorCorrections",corrections);
        analysis.put("gateDiagnostics",diagnostics); analysis.put("rowCounts",rowCounts(tables));
        analysis.put("stateSchema",platform.get("stateSchema"));
        analysis.put("parallelConclusion",status("parallel")); analysis.put("isolationConclusion",status("integrity"));
        analysis.put("coverageConclusion",status("coverage"));
        analysis.put("functionalConclusion",status("functional")); analysis.put("overallStatus",status("parallel","integrity","coverage","functional"));
        Map<String,Long> counts=new LinkedHashMap<>(); checks.forEach(c->counts.merge(text(c,"status"),1L,Long::sum)); analysis.put("checkCounts",counts);
        List<Object> limits=new ArrayList<>(list(map(optional(runDir.resolve("environment.json")).get("coverage")).get("notCovered")));
        limits.addAll(list(platform.get("limitations")));
        limits.add("SSE times measure client receipt, not provider-internal execution.");
        limits.add("Agent object identity is not observable through these endpoints; singleton reuse remains a source/runtime-classpath premise.");
        limits.add("Native dynamic tool groups, ToolEmitter, forced context compaction, multi-node failover, restart recovery and capacity limits are not covered.");
        limits.add("Marker scans cover exported test-user records; marker absence is not universal proof of noninterference.");
        limits.add("Fingerprint equality means no net changes between snapshots; transient/reverted writes remain outside this test.");
        analysis.put("limitations",limits); analysis.put("originalEvidencePreserved",true);
    }

    private void loadDiagnostics() throws IOException {
        try(var paths=Files.list(runDir)) {
            for(Path dir:paths.filter(p->p.getFileName().toString().startsWith("gate-diagnostic-")&&Files.isDirectory(p)).sorted().toList()) {
                Path file=dir.resolve("diagnostic.json"); if(!Files.isRegularFile(file))continue;
                Map<String,Object> diagnostic=objectFile(file); diagnostics.add(diagnostic);
                supplementaryRejected+=objects(diagnostic.get("rejections")).size();
                for(String key:List.of("targetResult","survivorResult")) {
                    Map<String,Object> result=map(diagnostic.get(key)); if(result.isEmpty())continue;
                    String user=markers.entrySet().stream().filter(e->text(result,"question").contains(e.getValue())).map(Map.Entry::getKey).findFirst().orElse("");
                    check(dir.getFileName()+"."+key+".identity","integrity",user.isBlank()?"UNKNOWN":"PASS","Supplementary result owner matched independent test marker.");
                    turns.add(new Turn(fields("name",dir.getFileName()+"-"+key,"userId",user,"question",result.get("question"),
                            "expected",bool(result,"cancelled")?"CANCELLED":"NORMAL","result",result)));
                }
                for(Map<String,Object> reject:objects(diagnostic.get("rejections"))) {
                    boolean active=bool(reject,"activeABeforeRequest")&&bool(reject,"activeAAfterRequest");
                    boolean mixed=text(reject,"accept").contains("application/json");
                    boolean explicit=text(reject,"body").contains("处理中")&&number(reject.get("httpStatus"))==200;
                    check(dir.getFileName()+".gate."+text(reject,"accept")+"."+reject.get("sameConversation"),mixed?"integrity":"functional",
                            active&&explicit? "PASS":"FAIL",
                            "Active throughout="+active+", HTTP="+reject.get("httpStatus")+", body="+text(reject,"body")
                                    +". Strict SSE empty HTTP 500 is an error-reporting/media-negotiation issue, not evidence that the user gate allowed the request.");
                }
            }
        }
    }

    private void readFrames(Turn turn) throws IOException {
        Path file=runDir.resolve("sse").resolve(turn.name+".jsonl");
        String saved=text(turn.result,"framesFile");
        if(!Files.isRegularFile(file)&&!saved.isBlank()) {
            Path candidate=Path.of(saved).toAbsolutePath().normalize();
            if(candidate.startsWith(runDir)) file=candidate;
        }
        if(!Files.isRegularFile(file)) {check(turn.name+".raw","coverage","UNCOVERED","No raw JSONL evidence.");return;}
        turn.rawAvailable=true; String raw=Files.readString(file,StandardCharsets.UTF_8);
        scan(turn.name+".raw",turn.user,raw);
        StringBuilder answer=new StringBuilder(),reasoning=new StringBuilder();
        Map<String,StringBuilder> toolText=new LinkedHashMap<>();
        int index=0;
        for(String line:raw.split("\\R")) {
            if(line.isBlank())continue;index++;
            Map<String,Object> frame;
            try{frame=SimpleJson.object(SimpleJson.parse(line));}
            catch(RuntimeException e){check(turn.name+".frame-"+index,"integrity","FAIL","Malformed JSONL: "+e.getMessage());continue;}
            if(!text(frame,"kind").equals("frame"))continue;
            String event=text(frame,"event"),data=text(frame,"data");
            Map<String,Object> payload=parseObject(data);
            scan(turn.name+".frame-"+index,turn.user,strings(payload));
            if(event.equals("done")||data.equals("[DONE]"))turn.done=true;
            if(event.equals("finish"))turn.finish=true;
            if(event.equals("cancel")) {
                turn.cancel=true;
                if(text(turn.result,"messageId").isBlank()&&!text(payload,"messageId").isBlank()) {
                    turn.result.put("messageId",payload.get("messageId"));turn.result.put("messageStatus",payload.get("messageStatus"));
                    corrections.add(fields("turn",turn.name,"kind","cancel_completion_payload","detail","Recovered messageId/status from raw cancel; real protocol is cancel(payload)+done without finish. Original run.json unchanged."));
                }
            }
            if(event.equals("meta")&&(!text(payload,"taskId").equals(turn.task())||!text(payload,"conversationId").equals(turn.conversation())))
                check(turn.name+".meta-owner","integrity","FAIL","Raw metadata changed task/conversation ownership.");
            if(event.equals("message")||event.isBlank()) {
                String delta=text(payload,"delta");
                if(!delta.isEmpty()) {
                    boolean thinking=text(payload,"type").equals("reasoning");(thinking?reasoning:answer).append(delta);
                    long at=number(frame.get("atEpochMillis"));if(at>0)turn.tokens.add(new Token(at,thinking?"reasoning":"answer"));
                }
            }
            if(event.equals("block")&&text(payload,"kind").equals("tool")) {
                Map<String,Object> update=new LinkedHashMap<>(payload);update.put("receivedAtEpochMillis",frame.get("atEpochMillis"));turn.tools.add(update);
                String key=text(payload,"toolCallId");toolText.computeIfAbsent(key,k->new StringBuilder()).append(text(payload,"result")).append(text(payload,"text"));
            }
            if(event.equals("error")||event.equals("reject")||event.equals("block")&&text(payload,"kind").equals("error"))turn.serverError=true;
        }
        turn.tokens.sort(Comparator.comparingLong(Token::at));
        scan(turn.name+".assembled-answer",turn.user,answer.toString());scan(turn.name+".assembled-reasoning",turn.user,reasoning.toString());
        toolText.forEach((key,value)->scan(turn.name+".assembled-tool-"+key,turn.user,value.toString()));
        check(turn.name+".raw-result-match","integrity",answer.toString().equals(text(turn.result,"answer"))&&reasoning.toString().equals(text(turn.result,"reasoning"))?"PASS":"FAIL",
                "Reassembled answer and reasoning must exactly equal captured result; fragmented foreign markers are scanned after assembly.");
    }

    private void auditTurn(Turn turn) {
        scan(turn.name+".all-result",turn.user,strings(turn.result));
        check(turn.name+".ended","integrity",bool(turn.result,"ended")?"PASS":"FAIL","Every request has a final collector result.");
        if(turn.expected.equals("REJECTED")) {
            String body=text(turn.result,"responseBody")+strings(turn.result.get("responseJson"));
            boolean refusal=turn.task().isBlank()&&turn.conversation().isBlank()&&(body.contains("处理中")||body.contains("执行中"));
            check(turn.name+".rejection-feedback","functional",refusal?"PASS":"FAIL","Expected explicit pre-stream business rejection; HTTP="+turn.result.get("httpStatus")+", body="+body);
            check(turn.name+".no-accepted-identity","integrity",turn.task().isBlank()&&turn.conversation().isBlank()?"PASS":"FAIL","Rejected request must have no accepted task/conversation.");
            return;
        }
        boolean cancelled=turn.expected.equals("CANCELLED"), valid=turn.done&&(cancelled?turn.cancel:turn.finish)
                &&!turn.message().isBlank()&&!bool(turn.result,"timedOut")&&!turn.serverError;
        List<Map<String,Object>> errors=new ArrayList<>();
        for(Map<String,Object> error:objects(turn.result.get("errors"))) {
            if(cancelled&&turn.cancel&&turn.done&&!turn.message().isBlank()&&Set.of("missing_finish","missing_message_id").contains(text(error,"type")))continue;
            errors.add(error);
        }
        valid&=errors.isEmpty()&&(cancelled?"INTERRUPTED":"NORMAL").equals(text(turn.result,"messageStatus"));
        if(!cancelled)valid&=bool(turn.result,"successful");
        check(turn.name+".terminal-protocol","integrity",valid?"PASS":"FAIL","Expected="+turn.expected+", raw done="+turn.done+", message="+turn.message()+", status="+turn.result.get("messageStatus")+", errors="+SimpleJson.stringify(errors));
        if(!cancelled&&turn.name.startsWith("parallel-")) {
            String marker=markers.get(turn.user);
            check(turn.name+".own-marker","functional",marker!=null&&text(turn.result,"answer").contains(marker)?"PASS":"FAIL",
                    "Own-marker output is a functional/recall assertion; failure does not negate observed concurrency or establish a foreign-user leak.");
        }
    }

    private void auditPersistence() {
        Map<String,String> tasks=new HashMap<>(),messages=new HashMap<>(),conversations=new HashMap<>();
        Map<String,Map<String,Object>> saved=new HashMap<>();rows("t_agent_message").forEach(r->saved.put(text(r,"id"),r));
        for(Turn turn:turns) {
            unique(turn,"task",turn.task(),tasks,false);unique(turn,"message",turn.message(),messages,false);unique(turn,"conversation",turn.conversation(),conversations,true);
            if(turn.expected.equals("REJECTED")) {
                boolean exists=rows("t_agent_message").stream().anyMatch(r->turn.user.equals(text(r,"user_id"))&&turn.question.equals(text(r,"content")));
                check(turn.name+".rejected-input-not-persisted","integrity",platform.isEmpty()?"UNCOVERED":exists?"FAIL":"PASS","Rejected prompt must not append a user message.");continue;
            }
            Map<String,Object> row=saved.get(turn.message());
            boolean owner=row!=null&&turn.user.equals(text(row,"user_id"))&&turn.conversation().equals(text(row,"conversation_id"))&&text(row,"role").equalsIgnoreCase("assistant");
            check(turn.name+".persisted-owner","integrity",platform.isEmpty()?"UNCOVERED":owner?"PASS":"FAIL","Completion ID resolves to same user, conversation and assistant role.");
            if(row!=null) {
                String expected=turn.expected.equals("CANCELLED")?"INTERRUPTED":"NORMAL";
                check(turn.name+".persisted-status","integrity",expected.equals(text(row,"message_status"))?"PASS":"FAIL","Expected="+expected+", actual="+row.get("message_status"));
                check(turn.name+".persisted-answer","integrity",text(row,"content").equals(text(turn.result,"answer"))?"PASS":"FAIL","Persisted assistant content equals SSE answer exactly.");
                Map<String,Object> input=saved.get(text(row,"reply_to_message_id"));
                boolean paired=input!=null&&text(input,"role").equalsIgnoreCase("user")&&turn.user.equals(text(input,"user_id"))
                        &&turn.conversation().equals(text(input,"conversation_id"))&&turn.question.equals(text(input,"content"));
                check(turn.name+".reply-input-correspondence","integrity",paired?"PASS":"FAIL",
                        "Assistant reply_to_message_id points to the same user's exact submitted question in the same conversation.");
            }
            List<Map<String,Object>> states=rows("t_agent_state").stream().filter(r->turn.user.equals(owner(r))&&turn.conversation().equals(conversation(r))).toList();
            check(turn.name+".state-present","coverage",states.isEmpty()?"UNCOVERED":"PASS","Owned persisted Agent state rows="+states.size());
            if(!states.isEmpty()) {
                String answer=text(turn.result,"answer");
                boolean cancelled=turn.expected.equals("CANCELLED");
                boolean found=states.stream().anyMatch(r->stateTurnAnswerMatches(r,turn.question,answer)
                        ||strings(r).contains(turn.message())||!answer.isBlank()&&strings(r).contains(answer)
                        ||cancelled&&!turn.question.isBlank()&&strings(r).contains(turn.question));
                check(turn.name+".state-message-correspondence","coverage",found?"PASS":"UNCOVERED",
                        cancelled?"Cancelled state retains the submitted user input; incomplete assistant output need not be committed to SDK context."
                                :"State contains the persisted ID, exact answer, or the same USER turn's ordered assistant TextBlocks; SDK message IDs may differ from platform IDs.");
            }
        }
        Set<String> expectedAssistantIds=new HashSet<>();
        turns.stream().filter(t->!t.expected.equals("REJECTED")&&!t.message().isBlank()).forEach(t->expectedAssistantIds.add(t.message()));
        List<Map<String,Object>> assistants=rows("t_agent_message").stream().filter(r->text(r,"role").equalsIgnoreCase("assistant")).toList();
        Set<String> actualAssistantIds=new HashSet<>();assistants.forEach(r->actualAssistantIds.add(text(r,"id")));
        long userMessages=rows("t_agent_message").stream().filter(r->text(r,"role").equalsIgnoreCase("user")).count();
        check("database.exact-request-message-accounting","integrity",platform.isEmpty()?"UNCOVERED":
                actualAssistantIds.equals(expectedAssistantIds)&&userMessages==expectedAssistantIds.size()?"PASS":"FAIL",
                "Accepted replies="+expectedAssistantIds.size()+", persisted assistants="+assistants.size()+", persisted user inputs="+userMessages
                        +"; includes supplementary diagnostics, excludes all rejected inputs.");
    }

    private void auditBoundaries() throws IOException {
        List<Object> boundaries=new ArrayList<>();
        try(var paths=Files.list(runDir)) {
            for(Path file:paths.filter(p->p.getFileName().toString().startsWith("platform-")&&p.toString().endsWith(".json")).sorted().toList()) {
                Map<String,Object> snapshot=objectFile(file);auditSnapshot(file.getFileName().toString(),snapshot,false);
                boundaries.add(fields("file",file.getFileName().toString(),"sampledAt",snapshot.get("sampledAt"),"rowCounts",rowCounts(map(snapshot.get("tables")))));
            }
        }
        auditSnapshot("selected-final",platform,true);analysis.put("boundarySnapshots",boundaries);
        check("state.live-schema","coverage",bool(map(platform.get("stateSchema")),"compatible")?"PASS":"LIMITATION",
                "Selected snapshot schema and verified ownership encoding: "+SimpleJson.stringify(platform.get("stateSchema")));
    }

    private void auditSnapshot(String label,Map<String,Object> snapshot,boolean finalSnapshot) {
        if(snapshot.isEmpty())return;Map<String,Object> scoped=map(snapshot.get("tables"));
        for(Map.Entry<String,Object> table:scoped.entrySet())for(Map<String,Object> row:objects(table.getValue())) {
            String user=table.getKey().equals("t_agent_state")?owner(row):text(row,"user_id");
            scan(label+"."+table.getKey()+"."+text(row,"id"),user,strings(row));
        }
        for(Map<String,Object> violation:objects(snapshot.get("violations"))) {
            boolean schema=text(violation,"kind").toLowerCase().contains("schema");
            if(schema&&!finalSnapshot)corrections.add(fields("snapshot",label,"kind","historical-schema-probe-limitation","detail",violation,
                    "selectedSnapshotHasStates",!rows("t_agent_state").isEmpty()));
            else check(label+".violation-"+text(violation,"kind"),schema?"coverage":"integrity",schema?"LIMITATION":"FAIL",SimpleJson.stringify(violation));
        }
        if(finalSnapshot) {
            long nonterminal=objects(scoped.get("t_agent_message")).stream().filter(r->!Set.of("NORMAL","INTERRUPTED","CANCELLED").contains(text(r,"message_status"))).count();
            long processing=objects(scoped.get("t_agent_memory_extraction")).stream().filter(r->text(r,"status").equals("PROCESSING")).count();
            check("database.final-terminal","integrity",nonterminal==0&&objects(snapshot.get("nonterminalMessages")).isEmpty()?"PASS":"FAIL","Nonterminal messages="+nonterminal);
            check("database.final-processing","integrity",processing==0?"PASS":"FAIL","PROCESSING extractions="+processing);
            check("database.relationships","integrity",objects(snapshot.get("relationshipViolations")).isEmpty()?"PASS":"FAIL",SimpleJson.stringify(snapshot.get("relationshipViolations")));
        }
    }

    private void auditMemoryAndTools() {
        for(Map.Entry<String,String> identity:markers.entrySet()) {
            List<Map<String,Object>> active=rows("t_agent_memory").stream().filter(r->identity.getKey().equals(text(r,"user_id"))&&r.get("invalid_at")==null).toList();
            boolean saved=active.stream().anyMatch(r->text(r,"content").contains(identity.getValue()));
            check("memory."+identity.getKey()+".active-marker","coverage",saved?"PASS":"UNCOVERED","Own marker must actually exist in active memory; active rows="+active.size());
            List<Turn> recall=turns.stream().filter(t->t.user.equals(identity.getKey())&&t.name.startsWith("parallel-memory-recall")).toList();
            Set<String> prior=new HashSet<>();turns.stream().filter(t->t.user.equals(identity.getKey())&&t.name.startsWith("parallel-memory-write")).forEach(t->prior.add(t.conversation()));
            boolean fresh=!recall.isEmpty()&&recall.stream().allMatch(t->text(t.result,"requestedConversationId").isBlank()&&!prior.contains(t.conversation()));
            check("memory."+identity.getKey()+".fresh-session","coverage",fresh?"PASS":"UNCOVERED","Recall is a genuinely new conversation.");
            check("memory."+identity.getKey()+".recall","functional",!recall.isEmpty()&&recall.stream().allMatch(t->text(t.result,"answer").contains(identity.getValue()))?"PASS":"FAIL","Fresh-session answer recalls own persisted marker.");
        }
        List<Object> coverage=new ArrayList<>();
        for(String name:List.of("search_product","search_knowledge","query_order","query_cart","flush_memory")) {
            Set<String> users=new LinkedHashSet<>();List<Map<String,Object>> calls=new ArrayList<>(),intervals=new ArrayList<>();
            for(Turn turn:turns) {
                Map<String,Long> running=new HashMap<>();
                for(Map<String,Object> tool:turn.tools) {
                    if(!name.equals(text(tool,"name")))continue;String id=text(tool,"toolCallId");
                    if(text(tool,"status").equalsIgnoreCase("running"))running.putIfAbsent(id,number(tool.get("receivedAtEpochMillis")));
                    if(!success(tool))continue;users.add(turn.user);
                    calls.add(fields("turn",turn.name,"userId",turn.user,"toolCallId",id,"status",tool.get("status"),"result",tool.get("result")));
                    long start=number(tool.get("startedAt")),end=number(tool.get("endedAt"));String clock="server-tool-execution";
                    if(start<=0||end<=0){start=running.getOrDefault(id,0L);end=number(tool.get("receivedAtEpochMillis"));clock="client-tool-events";}
                    if(start>0&&end>=start)intervals.add(fields("turn",turn.name,"userId",turn.user,"start",start,"end",end,"clock",clock));
                    if(Set.of("query_order","query_cart").contains(name)) {
                        String output=text(tool,"result"),empty=name.equals("query_order")?"当前账号名下还没有订单":"购物车是空的";
                        check(turn.name+"."+name+".empty-owned-result","integrity",output.contains(empty)||emptyJson(output)?"PASS":"UNKNOWN",
                                "Actual successful tool result, not model prose: "+output);
                    }
                }
            }
            List<Object> overlaps=new ArrayList<>();
            for(int i=0;i<intervals.size();i++)for(int j=i+1;j<intervals.size();j++) {
                Map<String,Object>a=intervals.get(i),b=intervals.get(j);
                long duration=intersection(number(a.get("start")),number(a.get("end")),number(b.get("start")),number(b.get("end")));
                if(!a.get("userId").equals(b.get("userId"))&&a.get("clock").equals(b.get("clock"))&&duration>0)overlaps.add(fields("a",a,"b",b,"overlapMillis",duration));
            }
            check("tool."+name+".multi-user-success","coverage",users.size()>=2?"PASS":"UNCOVERED","Only done/SUCCESS counts; successful users="+users.size());
            check("tool."+name+".execution-overlap","coverage",overlaps.isEmpty()?"UNCOVERED":"PASS","Tool execution/event overlap pairs="+overlaps.size()+"; fast sequential tools do not invalidate Agent/model output parallelism.");
            coverage.add(fields("tool",name,"successfulUsers",users,"successfulCalls",calls,"executionOverlaps",overlaps));
        }
        analysis.put("toolCoverage",coverage);
    }

    private void auditBusiness() throws IOException {
        Map<String,Object> before=optional(runDir.resolve("business-before.json")),afterBusiness=map(business.get("business"));
        Map<String,Object> beforeTables=map(map(before.get("business")).get("tables")),afterTables=map(afterBusiness.get("tables"));
        List<Object> comparisons=new ArrayList<>();
        for(Map.Entry<String,Object> item:beforeTables.entrySet()) {
            Object after=afterTables.get(item.getKey());boolean equal=after!=null&&map(item.getValue()).equals(map(after));
            check("business."+item.getKey()+".net-change","integrity",after==null?"UNCOVERED":equal?"PASS":"UNKNOWN","Whole-table equality proves only no net changes; differences cannot be attributed to this run or exclude other actors.");
            comparisons.add(fields("table",item.getKey(),"before",item.getValue(),"after",after,"unchanged",equal));
        }
        if(beforeTables.isEmpty())check("business.baseline","coverage","UNCOVERED","Business fingerprints unavailable.");
        Object vectorBefore=map(before.get("pgvector")).get("t_knowledge_vector"),vectorAfter=map(business.get("pgvector")).get("t_knowledge_vector");
        check("pgvector.net-change","integrity",vectorBefore==null||vectorAfter==null?"UNCOVERED":map(vectorBefore).equals(map(vectorAfter))?"PASS":"UNKNOWN","Fingerprint equality is no-net-change evidence only.");
        comparisons.add(fields("table","t_knowledge_vector","before",vectorBefore,"after",vectorAfter,
                "unchanged",vectorBefore!=null&&vectorAfter!=null&&map(vectorBefore).equals(map(vectorAfter))));
        for(String user:markers.keySet()) {
            List<Map<String,Object>> counts=objects(afterBusiness.get("testUserCounts")).stream().filter(r->user.equals(text(r,"userId"))).toList();
            boolean zero=counts.size()==1&&counts.get(0).entrySet().stream().filter(e->!e.getKey().equals("userId")).allMatch(e->e.getValue() instanceof Number&&number(e.getValue())==0);
            check("business."+user+".zero-owned-records","integrity",counts.isEmpty()?"UNCOVERED":zero?"PASS":"FAIL","New-user business counts="+SimpleJson.stringify(counts));
        }
        analysis.put("aggregateFingerprints",comparisons);analysis.put("businessTestUserCounts",afterBusiness.get("testUserCounts"));
    }

    private void auditParallelAndRedis() throws IOException {
        List<Map<String,Object>> samples=jsonLines(runDir.resolve("redis-samples.jsonl"));
        Map<String,Integer> maxima=new LinkedHashMap<>();int global=0,errors=0;
        for(Map<String,Object> sample:samples) {
            if(sample.containsKey("error")||!bool(sample,"atomic")){errors++;continue;}
            int active=(int)number(sample.get("activeUserCount"));global=Math.max(global,active);maxima.merge(text(sample,"phase"),active,Math::max);
        }
        check("parallel.atomic-redis","parallel",global>=2?"PASS":"UNCOVERED","Maximum simultaneous running test users from atomic samples="+global);
        if(errors>0)check("redis.sample-errors","coverage","UNKNOWN","Errored/non-atomic samples="+errors);
        List<Map<String,Object>> overlaps=new ArrayList<>(),common=new ArrayList<>();
        for(int i=0;i<turns.size();i++)for(int j=i+1;j<turns.size();j++) {
            Turn a=turns.get(i),b=turns.get(j);if(a.user.equals(b.user)||!a.phase().equals(b.phase())||a.tokens.size()<2||b.tokens.size()<2)continue;
            Map<String,Object> pair=modelOverlap(a,b);if(number(pair.get("overlapMillis"))>0)overlaps.add(pair);
        }
        check("parallel.interleaved-output","parallel",overlaps.stream().anyMatch(p->number(p.get("alternations"))>=2)?"PASS":"UNCOVERED",
                "Independent SSE answer/reasoning intervals overlap with strictly increasing A/B/A arrivals; these are client receipt times, not provider internal times.");
        for(String phase:List.of("parallel-seed","parallel-history","parallel-tools","parallel-user-tools","parallel-memory-write","parallel-memory-recall")) {
            List<Turn> wave=turns.stream().filter(t->t.phase().equals(phase)&&!t.tokens.isEmpty()).toList();
            long start=wave.stream().mapToLong(t->t.tokens.get(0).at).max().orElse(0),end=wave.stream().mapToLong(t->t.tokens.get(t.tokens.size()-1).at).min().orElse(0);
            common.add(fields("phase",phase,"users",wave.stream().map(t->t.user).distinct().count(),"commonOutputOverlapMillis",Math.max(0,end-start)));
            boolean interleaving=overlaps.stream().anyMatch(p->phase.equals(p.get("phase"))&&number(p.get("alternations"))>=2);
            check(phase+".server-overlap","coverage",maxima.getOrDefault(phase,0)>=2?"PASS":"UNCOVERED","Atomic max users="+maxima.getOrDefault(phase,0));
            check(phase+".output-overlap","coverage",interleaving?"PASS":"UNCOVERED","Client model-output common window="+Math.max(0,end-start)+" ms; users="+wave.size());
        }
        analysis.put("redisMaxActiveUsers",global);analysis.put("redisMaxActiveUsersByPhase",maxima);analysis.put("modelOutputOverlaps",overlaps);analysis.put("commonOutputWindows",common);
        boolean clear=!redis.isEmpty()&&bool(redis,"atomic")&&number(redis.get("activeUserCount"))==0&&objects(redis.get("users")).size()>=markers.size()
                &&objects(redis.get("users")).stream().noneMatch(r->bool(r,"exists"));
        check("redis.final-running-released","integrity",redis.isEmpty()?"UNCOVERED":clear?"PASS":"FAIL","All test users' running keys absent after settling.");
        Set<String> expected=new HashSet<>(),seen=new HashSet<>();turns.forEach(t->{if(!t.task().isBlank())expected.add(t.task());});
        boolean keysClear=true;
        for(Map<String,Object> task:objects(redis.get("tasks"))){seen.add(text(task,"taskId"));if(bool(map(task.get("owner")),"exists")||bool(map(task.get("cancel")),"exists"))keysClear=false;}
        check("redis.final-owner-cancel-released","integrity",!seen.containsAll(expected)?"UNCOVERED":keysClear?"PASS":"FAIL","Expected tasks="+expected.size()+", sampled="+seen.size());
        analysis.put("redisFinal",fields("sampledAt",redis.get("sampledAt"),"activeUserCount",redis.get("activeUserCount"),
                "sampledUsers",objects(redis.get("users")).size(),"expectedTasks",expected.size(),"sampledTasks",seen.size(),
                "ownerAndCancelKeysAbsent",keysClear,"allExpectedTasksSampled",seen.containsAll(expected)));
    }

    private void auditGateAndCancel() {
        Turn active=find("gate-active");
        for(String name:List.of("gate-same-conversation","gate-other-conversation")) {
            Turn reject=find(name);
            boolean during=active!=null&&reject!=null&&time(reject,"request")>=time(active,"meta")&&time(reject,"request")<time(active,"ended")&&time(active,"meta")>0;
            check(name+".overlap-precondition","coverage",during?"PASS":"UNCOVERED","Conflicting request was submitted between active meta and ended timestamps.");
        }
        Turn target=find("cancel-target"),survivor=find("cancel-survivor");String foreign=text(evidence,"foreignStopError");
        boolean denied=foreign.contains("任务不存在或已结束")||foreign.contains("权限")||foreign.contains("403");
        boolean targetCancelled=target!=null&&target.cancel&&target.done&&!target.message().isBlank();
        boolean survived=survivor!=null&&bool(survivor.result,"successful")&&survivor.done&&!survivor.cancel;
        check("cancel.foreign-stop-rejected","integrity",denied&&targetCancelled&&survived?"PASS":"UNKNOWN","Foreign stop="+foreign+"; owner cancellation and unrelated user's normal completion independently verified.");
        if(foreign.contains("任务不存在或已结束")&&originalChecks.stream().anyMatch(c->!text(c,"status").equals("PASS")
                &&(text(c,"name").contains("foreign-stop")||text(c,"name").equals("cancel.foreign-user-denied"))))
            corrections.add(fields("kind","foreign-stop-rejection-semantics","detail","Service intentionally hides task existence; this message is a valid ownership rejection, unlike older runner permission-only matching."));
        check("cancel.unrelated-user-completed","integrity",survived?"PASS":"FAIL","Non-target stream completes normally.");
        boolean overlap=target!=null&&survivor!=null&&target.tokens.size()>1&&survivor.tokens.size()>1&&number(modelOverlap(target,survivor).get("overlapMillis"))>0;
        check("cancel.model-output-overlap","coverage",overlap?"PASS":"UNCOVERED","Target and survivor have overlapping observed model-output windows.");
    }

    private void retainOriginalChecks() {
        List<Object> original=new ArrayList<>();
        for(Map<String,Object> item:originalChecks) {
            String name=text(item,"name"),result=text(item,"status"),detail=text(item,"detail");if(result.equals("PASS"))continue;
            boolean superseded=name.equals("database.platform-integrity")&&detail.contains("incompatibleStateSchema")
                    ||(name.contains("foreign-stop")||name.equals("cancel.foreign-user-denied"))&&text(evidence,"foreignStopError").contains("任务不存在或已结束");
            original.add(fields("original",item,"disposition",superseded?"superseded_by_explicit_recheck":"retained"));
            if(!superseded)check("runner."+name,name.contains("rejected")||name.endsWith("own-marker")?"functional":Set.of("UNKNOWN","UNCOVERED").contains(result)?"coverage":"integrity",result,detail);
        }
        analysis.put("originalNonPassChecks",original);
    }

    private static Map<String,Object> modelOverlap(Turn a,Turn b) {
        long start=Math.max(a.tokens.get(0).at,b.tokens.get(0).at),end=Math.min(a.tokens.get(a.tokens.size()-1).at,b.tokens.get(b.tokens.size()-1).at);
        List<Map<String,Object>> arrivals=new ArrayList<>();
        for(Turn t:List.of(a,b))for(Token token:t.tokens)if(token.at>=start&&token.at<=end)arrivals.add(fields("atEpochMillis",token.at,"turn",t.name,"type",token.type));
        arrivals.sort(Comparator.comparingLong(r->number(r.get("atEpochMillis"))));
        String previous="";long last=-1;int changes=0;List<Object> examples=new ArrayList<>();
        for(Map<String,Object> event:arrivals){long at=number(event.get("atEpochMillis"));if(at<=last)continue;
            String current=text(event,"turn");if(!current.equals(previous)){if(!previous.isEmpty())changes++;if(examples.size()<12)examples.add(event);previous=current;}last=at;}
        return fields("phase",a.phase(),"turnA",a.name,"turnB",b.name,"overlapMillis",Math.max(0,end-start),"startEpochMillis",start,"endEpochMillis",end,
                "alternations",changes,"alternatingArrivalExamples",examples,"clock","client raw SSE reception, not provider-internal timing");
    }

    private void scan(String name,String owner,String value) {
        if(owner.isBlank())return;List<String> foreign=new ArrayList<>();
        markers.forEach((user,marker)->{if(!user.equals(owner)&&!marker.isBlank()&&value.contains(marker))foreign.add(user);});
        if(!foreign.isEmpty()||!name.contains(".frame-"))check(name+".foreign-marker","integrity",foreign.isEmpty()?"PASS":"FAIL","Owner="+owner+", foreign marker owners="+foreign);
    }
    private void unique(Turn turn,String type,String id,Map<String,String> seen,boolean reusable) {
        if(id.isBlank())return;String previous=seen.putIfAbsent(id,reusable?turn.user:turn.name);
        check(turn.name+".unique-"+type,"integrity",previous==null||reusable&&previous.equals(turn.user)?"PASS":"FAIL",type+"="+id+", prior="+previous);
    }
    private void check(String name,String category,String status,String detail){checks.add(fields("name",name,"category",category,"status",status,"detail",detail));}
    private String status(String... categories) {
        Set<String> selected=Set.of(categories);List<Map<String,Object>> relevant=checks.stream().filter(c->selected.contains(text(c,"category"))).toList();
        if(relevant.stream().anyMatch(c->text(c,"status").equals("FAIL")))return "FAIL";
        return relevant.isEmpty()||relevant.stream().anyMatch(c->!text(c,"status").equals("PASS"))?"INCOMPLETE":"PASS";
    }
    private void save() throws IOException {
        write(runDir.resolve("analysis.json"),analysis);write(runDir.resolve("analysis-checks.json"),checks);
        StringBuilder md=new StringBuilder("# Ragent 真服务并发回归：独立离线复核\n\n生成时间：").append(analysis.get("generatedAt"))
                .append("\n\n**是否并行：").append(analysis.get("parallelConclusion")).append("。** Redis 原子采样最多同时有 ").append(analysis.get("redisMaxActiveUsers"))
                .append(" 个测试用户运行位；独立 SSE 原始时间线另验证了模型输出区间交叠与到达顺序交错。\n\n**已观测路径的数据隔离：")
                .append(analysis.get("isolationConclusion")).append("；覆盖完整性：").append(analysis.get("coverageConclusion"))
                .append("；功能行为：").append(analysis.get("functionalConclusion")).append("；总体：").append(analysis.get("overallStatus"))
                .append("。**\n\n测试用户：").append(markers.size()).append("；HTTP 聊天请求：").append(analysis.get("httpChatRequests")).append("（正常 ")
                .append(analysis.get("normalRequests")).append("、取消 ").append(analysis.get("cancelRequests")).append("、预期拒绝 ").append(analysis.get("rejectedRequests")).append("）。\n\n")
                .append("从第一性原理看，共享一个 Agent 对象是否正确，取决于每次执行会修改什么状态、这些状态按什么身份隔离，以及取消与清理能否只作用于目标请求。共享实例本身不能推出必须串行。\n\n")
                .append("因此本回归分别验证：不同用户有重叠执行证据；回答、历史、工具身份和持久状态归属正确；同用户受闸门约束；取消与清理不会波及其他用户。通过仅代表本次覆盖的运行路径，不代表 SDK 的任意共享可变组件都线程安全。\n\n")
                .append("SSE 时间是客户端接收时间，不代表供应商内部推理计时。own-marker 漏复述属于功能/记忆结果，不能据此否定并行；foreign marker 才直接指向测试用户之间的数据污染。\n\n")
                .append("## 每阶段并行证据\n\n| 阶段 | Redis 最大运行用户 | 所有用户输出共同区间 ms |\n|---|---:|---:|\n");
        Map<String,Object> maxima=map(analysis.get("redisMaxActiveUsersByPhase"));
        for(Map<String,Object> wave:objects(analysis.get("commonOutputWindows")))md.append('|').append(wave.get("phase")).append('|').append(maxima.getOrDefault(text(wave,"phase"),0)).append('|').append(wave.get("commonOutputOverlapMillis")).append("|\n");
        md.append("\n## 实际工具覆盖\n\n| 工具 | SUCCESS 用户数 | 执行区间交叠配对 |\n|---|---:|---:|\n");
        for(Map<String,Object> tool:objects(analysis.get("toolCoverage")))md.append('|').append(tool.get("tool")).append('|').append(list(tool.get("successfulUsers")).size()).append('|').append(list(tool.get("executionOverlaps")).size()).append("|\n");
        md.append("\n仅真实 done/SUCCESS 工具终态计入覆盖；快速查询没有可观测交叠时记 UNCOVERED，不把工具名称出现当成执行成功。\n\n## 未通过与未覆盖项\n\n| 检查 | 类型 | 结果 | 依据 |\n|---|---|---|---|\n");
        for(Map<String,Object> c:checks)if(!text(c,"status").equals("PASS"))md.append('|').append(escape(text(c,"name"))).append('|').append(c.get("category")).append('|').append(c.get("status")).append('|').append(escape(text(c,"detail"))).append("|\n");
        if(checks.stream().anyMatch(c->text(c,"status").equals("FAIL")))
            md.append("\n同一问题可能被多项断言捕获，FAIL 条数不等于独立缺陷数。\n");
        md.append("\n所有 UNKNOWN / UNCOVERED / LIMITATION 检查都使总体无法成为全面 PASS；覆盖不足与已观测路径的数据隔离、功能行为分别给出结论。\n\n")
                .append(corrections.isEmpty()?"## 持久化\n\n最终只读快照：":"## 持久化与采集修正\n\n最终只读快照：").append(snapshotDir).append("；时间：").append(platform.get("sampledAt"))
                .append("。\n\n| 平台表 | 本次账号范围的行数 |\n|---|---:|\n");
        for(Map.Entry<String,Object> count:map(analysis.get("rowCounts")).entrySet())
            md.append('|').append(count.getKey()).append('|').append(count.getValue()).append("|\n");
        Map<String,Object> stateSchema=map(platform.get("stateSchema"));
        md.append("\n当前 Agent state 布局：`").append(escape(text(stateSchema,"layout"))).append("`；与工作区存储结构兼容：")
                .append(stateSchema.get("checkoutCompatible")).append("；使用官方 user:session 复合键：").append(stateSchema.get("officialCompositeSession"))
                .append("；探针可解析：").append(stateSchema.get("compatible")).append("。\n\n")
                .append("所有已接受请求逐条核对 task/message 唯一性、持久化 owner/conversation、reply_to 原问题、助手正文与状态；取消须落 INTERRUPTED，最终不得残留非终态消息或 PROCESSING 提取。状态匹配支持按同一 USER 输入至下一 USER 输入的边界，顺序拼接多条 assistant TextBlock，跳过工具与推理内容。所有平台边界已扫描全字段 foreign marker。\n\n")
                .append("| 业务/向量表 | 开始行数 | 结束行数 | 完整行聚合指纹相同 |\n|---|---:|---:|---|\n");
        for(Map<String,Object> comparison:objects(analysis.get("aggregateFingerprints")))
            md.append('|').append(comparison.get("table")).append('|').append(map(comparison.get("before")).getOrDefault("rows","未采集")).append('|')
                    .append(map(comparison.get("after")).getOrDefault("rows","未采集")).append('|').append(Boolean.TRUE.equals(comparison.get("unchanged"))?"是":"否/未覆盖").append("|\n");
        md.append("\n业务库及 pgvector 的完整行聚合指纹相等只表示无净变化，不排除瞬时写后还原。变化不能仅凭该差异归因本次回归，其他用户和后台任务也可能参与。\n\n");
        Map<String,Object> finalRedis=map(analysis.get("redisFinal"));
        md.append("Redis 最终原子快照：").append(finalRedis.get("sampledUsers")).append(" 个用户运行位中，仍活跃 ")
                .append(finalRedis.get("activeUserCount")).append(" 个；检查 ").append(finalRedis.get("sampledTasks")).append(" 个任务（预期 ")
                .append(finalRedis.get("expectedTasks")).append("），owner/cancel 键全部释放：").append(finalRedis.get("ownerAndCancelKeysAbsent"))
                .append("。新测试账号各项业务记录计数及 query_order/query_cart 的真实空结果也已分别断言。\n\n");
        for(Map<String,Object> correction:corrections)md.append("- ").append(escape(SimpleJson.stringify(correction))).append('\n');
        md.append("\n原 run.json、checks.json、JSONL 均未改写。");
        if(!corrections.isEmpty())md.append("采集修正只在独立分析中体现。");
        md.append("\n\n## 结论边界\n\n");
        for(Object limit:list(analysis.get("limitations")))md.append("- ").append(escape(String.valueOf(limit))).append('\n');
        md.append("\n## 产物\n\n- [完整分析](analysis.json)\n- [分析断言](analysis-checks.json)\n- [原始请求](run.json)\n- [原始断言](checks.json)\n- [原子 Redis 采样](redis-samples.jsonl)\n");
        Files.writeString(runDir.resolve("report.md"),md,StandardCharsets.UTF_8);
    }

    private List<Map<String,Object>> rows(String table){return objects(tables.get(table));}
    private Turn find(String name){return turns.stream().filter(t->t.name.equals(name)).findFirst().orElse(null);}
    /** A ReAct turn can persist several assistant messages while SSE exposes their text as one answer. */
    private static boolean stateTurnAnswerMatches(Map<String,Object> state,String question,String answer) {
        if(question.isBlank()||answer.isBlank())return false;
        boolean inTurn=false;
        StringBuilder assembled=new StringBuilder();
        for(Map<String,Object> message:objects(map(state.get("payload")).get("context"))) {
            String role=text(message,"role");
            if(role.equalsIgnoreCase("USER")) {
                if(inTurn&&answer.contentEquals(assembled))return true;
                inTurn=question.equals(textBlocks(message));
                assembled.setLength(0);
            } else if(inTurn&&role.equalsIgnoreCase("ASSISTANT")) {
                assembled.append(textBlocks(message));
            }
        }
        return inTurn&&answer.contentEquals(assembled);
    }
    private static String textBlocks(Map<String,Object> message) {
        StringBuilder value=new StringBuilder();
        for(Map<String,Object> block:objects(message.get("content")))
            if(text(block,"type").equalsIgnoreCase("text"))value.append(text(block,"text"));
        return value.toString();
    }
    private String owner(Map<String,Object> row){String user=text(row,"user_id");if(!user.isBlank())return user;String session=text(row,"session_id");return markers.keySet().stream().filter(u->session.startsWith(u+":")).findFirst().orElse("");}
    private String conversation(Map<String,Object> row){String s=text(row,"session_id"),u=owner(row);return !u.isBlank()&&s.startsWith(u+":")?s.substring(u.length()+1):s;}
    private static boolean success(Map<String,Object> tool){return Set.of("done","success").contains(text(tool,"status").toLowerCase())&&!Boolean.FALSE.equals(tool.get("ok"));}
    private static boolean emptyJson(String value){Object parsed;try{parsed=SimpleJson.parse(value);}catch(RuntimeException e){return false;}if(parsed instanceof List<?> l)return l.isEmpty();Map<String,Object>m=map(parsed);return List.of("items","orders","records","lines","data").stream().anyMatch(k->m.get(k) instanceof List<?> l&&l.isEmpty());}
    private static long time(Turn turn,String stage){return number(turn.result.get(stage+"AtEpochMillis"));}
    private static long intersection(long a,long b,long c,long d){return Math.max(0,Math.min(b,d)-Math.max(a,c));}
    private static String strings(Object value){if(value==null)return "";if(value instanceof Map<?,?>m)return m.values().stream().map(ConcurrencyReportMain::strings).reduce("",(a,b)->a+"\n"+b);if(value instanceof Iterable<?>l){StringBuilder b=new StringBuilder();l.forEach(v->b.append(strings(v)).append('\n'));return b.toString();}return String.valueOf(value);}
    private static Map<String,Object> rowCounts(Map<String,Object> tables){Map<String,Object>m=new LinkedHashMap<>();tables.forEach((k,v)->m.put(k,list(v).size()));return m;}
    private static Map<String,Object> fields(Object... pairs){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?SimpleJson.object(value):Map.of();}
    private static List<?> list(Object value){return value instanceof List<?> l?l:value instanceof Set<?>s?new ArrayList<>(s):List.of();}
    private static List<Map<String,Object>> objects(Object value){return list(value).stream().map(ConcurrencyReportMain::map).toList();}
    private static String text(Map<String,Object>m,String k){return m.get(k)==null?"":String.valueOf(m.get(k));}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static boolean bool(Map<String,Object>m,String k){return Boolean.TRUE.equals(m.get(k));}
    private static Object read(Path file)throws IOException{return SimpleJson.parse(Files.readString(file,StandardCharsets.UTF_8));}
    private static Map<String,Object> objectFile(Path file)throws IOException{return SimpleJson.object(read(file));}
    private static Map<String,Object> optional(Path file)throws IOException{return Files.isRegularFile(file)?objectFile(file):Map.of();}
    private static Path latestSnapshot(Path directory) {
        if(!Files.isDirectory(directory))return directory;
        try(var paths=Files.list(directory)) {
            return paths.filter(p->Files.isDirectory(p)&&p.getFileName().toString().startsWith("recheck-")
                    &&Files.isRegularFile(p.resolve("platform.json"))&&Files.isRegularFile(p.resolve("redis.json"))
                    &&Files.isRegularFile(p.resolve("business.json"))).max(Comparator.comparing(p->p.getFileName().toString())).orElse(directory);
        } catch(IOException ignored){return directory;}
    }
    private static Map<String,Object> parseObject(String value){try{return map(SimpleJson.parse(value));}catch(RuntimeException e){return Map.of();}}
    private static List<Map<String,Object>> jsonLines(Path file)throws IOException{List<Map<String,Object>>r=new ArrayList<>();if(Files.isRegularFile(file))for(String line:Files.readAllLines(file))if(!line.isBlank())r.add(SimpleJson.object(SimpleJson.parse(line)));return r;}
    private static void write(Path file,Object value)throws IOException{Files.writeString(file,SimpleJson.stringify(value)+"\n",StandardCharsets.UTF_8);}
    private static String escape(String value){return value.replace("|","\\|").replace("\n"," ").replace("\r"," ");}
    private record Token(long at,String type){}
    private static final class Turn {
        final String name,user,expected,question;final Map<String,Object>result;final List<Token>tokens=new ArrayList<>();final List<Map<String,Object>>tools=new ArrayList<>();
        boolean rawAvailable,done,finish,cancel,serverError;
        Turn(Map<String,Object>row){name=text(row,"name");user=text(row,"userId");expected=text(row,"expected");question=text(row,"question");result=new LinkedHashMap<>(map(row.get("result")));}
        String task(){return text(result,"taskId");}String message(){return text(result,"messageId");}String conversation(){return text(result,"conversationId");}
        String phase(){return name.contains("-user-")?name.substring(0,name.lastIndexOf("-user-")):name.startsWith("cancel-")?"cancel-isolation":name;}
        Map<String,Object>summary(){return fields("name",name,"userId",user,"expected",expected,"taskId",task(),"conversationId",conversation(),"messageId",message(),"messageStatus",result.get("messageStatus"),"done",done,"cancel",cancel,"modelEvents",tokens.size(),"toolUpdates",tools.size());}
    }

    private static void selfTest() throws Exception {
        Path dir=Files.createTempDirectory("concurrency-report-selftest-");Files.createDirectories(dir.resolve("sse"));
        ConcurrencyReportMain report=new ConcurrencyReportMain(dir,null);String own="RGAAAAAAAAAAAAAAAA",foreign="RGBBBBBBBBBBBBBBBB";report.markers.put("a",own);report.markers.put("b",foreign);
        Turn a=new Turn(fields("name","parallel-test-user-1","userId","a","result",Map.of())),b=new Turn(fields("name","parallel-test-user-2","userId","b","result",Map.of()));
        a.tokens.addAll(List.of(new Token(100,"reasoning"),new Token(300,"answer"),new Token(500,"answer"),new Token(700,"answer")));
        b.tokens.addAll(List.of(new Token(200,"reasoning"),new Token(400,"answer"),new Token(600,"answer"),new Token(800,"answer")));
        require(number(modelOverlap(a,b).get("overlapMillis"))==500&&number(modelOverlap(a,b).get("alternations"))>=2,"Independent output interleaving");
        report.check("uncovered","coverage","UNCOVERED","test");report.check("own-marker","functional","FAIL","test");report.check("parallel","parallel","PASS","test");
        require(report.status("coverage").equals("INCOMPLETE")&&report.status("parallel").equals("PASS"),"Independent dimensions and UNCOVERED handling");
        require(success(fields("status","done"))&&!success(fields("status","running"))&&!success(fields("status","done","ok",false)),"Only successful terminal tools");
        require(report.owner(fields("session_id","a:session")).equals("a")&&report.conversation(fields("session_id","a:session")).equals("session"),"Composite SDK state key");
        require(new Turn(fields("name","parallel-user-tools-user-1","result",Map.of())).phase().equals("parallel-user-tools"),"Phase names containing user keep their full prefix");
        Map<String,Object> splitState=fields("payload",fields("context",List.of(
                fields("role","ASSISTANT","content",List.of(fields("type","text","text","before"))),
                fields("role","USER","content",List.of(fields("type","text","text","question"))),
                fields("role","ASSISTANT","content",List.of(fields("type","thinking","thinking","reasoning"),fields("type","text","text","prefix"))),
                fields("role","TOOL","content",List.of(fields("type","text","text","tool output"))),
                fields("role","ASSISTANT","content",List.of(fields("type","tool_use","text","tool argument"))),
                fields("role","ASSISTANT","content",List.of(fields("type","text","text","suffix"))),
                fields("role","USER","content",List.of(fields("type","text","text","next question"))),
                fields("role","ASSISTANT","content",List.of(fields("type","text","text","next answer"))))));
        require(stateTurnAnswerMatches(splitState,"question","prefixsuffix"),"Ordered same-turn assistant text is assembled across ReAct iterations");
        require(!stateTurnAnswerMatches(splitState,"question","prefixsuffixnext answer")
                &&!stateTurnAnswerMatches(splitState,"question","beforeprefixsuffix")
                &&!stateTurnAnswerMatches(splitState,"question","prefixreasoningtool outputtool argumentsuffix")
                &&!stateTurnAnswerMatches(splitState,"missing question","prefixsuffix"),"Never merge other USER turns, tools or reasoning into a matching answer");
        require(stateTurnAnswerMatches(splitState,"next question","next answer"),"Final user turn also matches without a following USER message");
        Turn cancel=new Turn(fields("name","cancel-target","userId","a","result",fields("answer","","reasoning","")));
        Files.writeString(dir.resolve("sse/cancel-target.jsonl"),SimpleJson.stringify(fields("kind","frame","event","cancel","data",SimpleJson.stringify(fields("messageId","m","messageStatus","INTERRUPTED"))))+"\n"
                +SimpleJson.stringify(fields("kind","frame","event","done","data","{}"))+"\n");
        report.readFrames(cancel);require(cancel.message().equals("m")&&cancel.cancel&&cancel.done,"Recover real cancel payload from old artifacts");
        Turn fragmented=new Turn(fields("name","fragmented","userId","a","result",fields("answer",foreign,"reasoning","")));StringBuilder frames=new StringBuilder();
        for(String delta:List.of(foreign.substring(0,8),foreign.substring(8)))frames.append(SimpleJson.stringify(fields("kind","frame","event","message","data",SimpleJson.stringify(fields("delta",delta))))).append('\n');
        Files.writeString(dir.resolve("sse/fragmented.jsonl"),frames);report.readFrames(fragmented);
        require(report.checks.stream().anyMatch(c->text(c,"name").contains("assembled-answer")&&text(c,"status").equals("FAIL")),"Split foreign marker detection");
        require(emptyJson("{\"orders\":[]}")&&!emptyJson("{\"orders\":[{\"id\":1}]}"),"Conservative empty-list parsing");
        System.out.println("Concurrency report self-test passed: interleaving, dimensions, uncovered, successful tools, state keys, same-turn text assembly, cancel correction, fragmented marker. Evidence="+dir);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
