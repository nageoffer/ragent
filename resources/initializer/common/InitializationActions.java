/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/** Reusable operations invoked by the small Main entry points. */
final class InitializationActions {

    private static final String INITIALIZER_LOCK_KEY = "ragent:initializer:lock";

    private static final String BIZ_SCHEMA_MISSING =
            "业务库表结构不存在，请先启动一次 mcp-server（它负责建表），或检查 ragent.bit.datasource.url";

    private InitializationActions() {
    }

    static void preflight(InitializerContext context) throws Exception {
        System.out.println("[preflight] 校验智能体类型数据 checksum");
        context.dataset().verifyChecksums();
        int documentCount = context.dataset().documentCount();
        int expectedDocuments = context.config().getInt("verification.document-count", documentCount);
        int expectedKnowledgeBases = context.config().getInt("verification.knowledge-base-count",
                context.dataset().knowledgeBases().size());
        int expectedIntents = context.config().getInt("verification.intent-count", context.dataset().intents().size());
        int expectedQuestions = context.config().getInt("verification.question-count",
                context.dataset().questions().size());
        require(documentCount == expectedDocuments,
                "文档数量与配置不一致，expected=" + expectedDocuments + ", actual=" + documentCount);
        require(context.dataset().knowledgeBases().size() == expectedKnowledgeBases,
                "知识库数量与配置不一致");
        require(context.dataset().intents().size() == expectedIntents,
                "意图数量与配置不一致");
        require(context.dataset().questions().size() == expectedQuestions,
                "演示问题数量与配置不一致，expected=" + expectedQuestions
                        + ", actual=" + context.dataset().questions().size());

        System.out.println("[preflight] 登录 RagentAI");
        context.loginAsAdmin();
        Map<String, Object> currentUser = SimpleJson.object(context.http().get("/user/me"));
        require("admin".equalsIgnoreCase(SimpleJson.string(currentUser, "role")), "当前用户不是 Admin");
        Map<String, Object> settings = SimpleJson.object(context.http().get("/rag/settings"));

        System.out.println("[preflight] 检查 PostgreSQL 和 Redis");
        require("PONG".equalsIgnoreCase(context.redis().ping()), "Redis 连通性检查失败");
        System.out.println("[preflight] Redis PONG");
        require(context.jdbc().queryLong("SELECT 1") == 1, "PostgreSQL 连通性检查失败");
        System.out.println("[preflight] " + context.jdbc().description());
        if (context.hasBizDatabase()) {
            requireBizSchema(context);
            System.out.println("[preflight] " + context.bizJdbc().description());
        }
        verifyBackendSettings(context, settings);
        assertIdle(context);

        System.out.printf("[preflight] 通过：knowledgeBases=%d, documents=%d, intents=%d, questions=%d, dryRun=%s%n",
                context.dataset().knowledgeBases().size(), documentCount, context.dataset().intents().size(),
                context.dataset().questions().size(), context.dryRun());
    }

    static void cleanup(InitializerContext context) throws Exception {
        context.requireConfirmation();
        assertIdle(context);
        if (context.dryRun()) {
            List<Map<String, Object>> bases = listKnowledgeBases(context);
            int documents = 0;
            for (Map<String, Object> base : bases) {
                documents += listDocuments(context, SimpleJson.string(base, "id")).size();
            }
            System.out.printf("[cleanup][dry-run] 将删除 knowledgeBases=%d, documents=%d，并执行 %s%s%n",
                    bases.size(), documents, context.agentTypeDir().resolve("cleanup.sql"),
                    context.hasBizDatabase() ? " 与 " + context.agentTypeDir().resolve("biz-cleanup.sql") : "");
            return;
        }

        int lockSeconds = context.config().getInt("cleanup.lock-seconds", 3600);
        if (!context.redis().acquireLock(INITIALIZER_LOCK_KEY, context.runId(), lockSeconds)) {
            throw new IllegalStateException("已有另一个初始化任务正在运行");
        }
        try {
            System.out.println("[cleanup] 逐文档调用 HTTP 删除接口，先同步回收源文件、Chunk 和索引");
            deleteAllDocuments(context);
            assertIdle(context);

            Path cleanupSql = context.agentTypeDir().resolve("cleanup.sql");
            System.out.println("[cleanup] 执行固定白名单 SQL: " + cleanupSql);
            context.jdbc().executeScript(cleanupSql);

            if (context.hasBizDatabase()) {
                Path bizCleanupSql = context.agentTypeDir().resolve("biz-cleanup.sql");
                System.out.println("[cleanup] 清空业务库数据表: " + bizCleanupSql);
                context.bizJdbc().executeScript(bizCleanupSql);
            }

            System.out.println("[cleanup] 精确清理 Ragent 缓存和已结束任务 Key");
            clearRedis(context);
            System.out.println("[cleanup] 完成");
        } finally {
            context.redis().releaseLock(INITIALIZER_LOCK_KEY, context.runId());
        }
    }

    /**
     * 灌业务库种子数据，先清空再按文件名顺序重灌，跑几次结果都一样
     * <p>
     * 表结构归 mcp-server 启动期建，这里只碰数据：建表放在启动路径上是非破坏的，清空重灌不是，
     * 混在一起就成了「重启一次清空一次订单」
     */
    static void initializeBizData(InitializerContext context) throws Exception {
        Path dataDir = context.agentTypeDir().resolve("biz-data");
        if (!Files.isDirectory(dataDir)) {
            System.out.println("[biz-data] 数据集没有业务库种子数据，跳过");
            return;
        }
        Path cleanupSql = context.agentTypeDir().resolve("biz-cleanup.sql");
        List<Path> scripts = sortedSqlFiles(dataDir);
        require(!scripts.isEmpty(), "biz-data 目录里没有 .sql 文件: " + dataDir);
        if (context.dryRun()) {
            System.out.printf("[biz-data][dry-run] 将执行 %s，再按序灌入 %d 个种子脚本%n",
                    cleanupSql, scripts.size());
            return;
        }
        requireBizSchema(context);

        String username = context.config().require("auth.username");
        String ownerId = resolveBizOwnerId(context, username);
        System.out.printf("[biz-data] 订单、购物车和券挂在 %s (id=%s) 名下，换账号登录看到的是空列表%n",
                username, ownerId);
        context.bizJdbc().executeScript(cleanupSql);
        System.out.println("[biz-data] 已清空业务数据表: " + cleanupSql.getFileName());
        Map<String, String> variables = Map.of("USER_ID", JdbcClient.literal(ownerId));
        for (Path script : scripts) {
            context.bizJdbc().executeScript(script, variables);
            System.out.println("[biz-data] 已灌入: " + script.getFileName());
        }
    }

    /**
     * 种子数据要挂在真人身上：工具一律按登录态圈数据，挂错人所有演示都只会回「未找到」
     */
    private static String resolveBizOwnerId(InitializerContext context, String username) throws Exception {
        List<List<String>> rows = context.jdbc().queryRows("SELECT id FROM t_user WHERE username = "
                + JdbcClient.literal(username) + " AND deleted = 0");
        require(!rows.isEmpty(), "平台库里没有用户 " + username + "，业务库种子数据没法挂到人身上");
        return rows.get(0).get(0);
    }

    /**
     * 表不在就换成能照着做的下一步，别把 PostgreSQL 原生的 relation does not exist 直接甩出来
     */
    private static void requireBizSchema(InitializerContext context) {
        long marker;
        try {
            marker = context.bizJdbc().queryLong("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = current_schema() AND table_name = 't_schema_version'");
        } catch (Exception ex) {
            throw new IllegalStateException(BIZ_SCHEMA_MISSING + "。连接业务库失败: " + ex.getMessage(), ex);
        }
        require(marker == 1, BIZ_SCHEMA_MISSING);
    }

    private static List<Path> sortedSqlFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    static void initializeKnowledgeBases(InitializerContext context) throws Exception {
        if (context.dryRun()) {
            for (InitializerDataset.KnowledgeBaseDefinition definition : context.dataset().knowledgeBases()) {
                System.out.printf("[knowledge-base][dry-run] create ref=%s name=%s collection=%s model=%s%n",
                        definition.ref(), definition.name(), definition.collectionName(), definition.embeddingModel());
            }
            return;
        }

        Map<String, Map<String, Object>> existingByCollection = new HashMap<>();
        for (Map<String, Object> existing : listKnowledgeBases(context)) {
            existingByCollection.put(SimpleJson.string(existing, "collectionName"), existing);
        }
        context.knowledgeBases().clear();
        for (InitializerDataset.KnowledgeBaseDefinition definition : context.dataset().knowledgeBases()) {
            Map<String, Object> existing = existingByCollection.get(definition.collectionName());
            String id;
            if (existing != null) {
                require(definition.name().equals(SimpleJson.string(existing, "name")),
                        "Collection 已存在但名称不同: " + definition.collectionName());
                require(definition.embeddingModel().equals(SimpleJson.string(existing, "embeddingModel")),
                        "Collection 已存在但嵌入模型不同: " + definition.collectionName());
                id = SimpleJson.string(existing, "id");
                System.out.println("[knowledge-base] 复用已存在知识库: " + definition.name() + " (" + id + ")");
            } else {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", definition.name());
                payload.put("embeddingModel", definition.embeddingModel());
                payload.put("collectionName", definition.collectionName());
                id = String.valueOf(context.http().postJson("/knowledge-base", payload));
                System.out.println("[knowledge-base] 已创建: " + definition.name() + " (" + id + ")");
            }
            require(id != null && !id.isBlank(), "知识库 ID 为空: " + definition.ref());
            context.knowledgeBases().put(definition.ref(),
                    new InitializerContext.KnowledgeBaseRuntime(id, definition.collectionName(), definition.name()));
        }
    }

    static void initializeDocuments(InitializerContext context) throws Exception {
        if (context.dryRun()) {
            for (InitializerDataset.KnowledgeBaseDefinition definition : context.dataset().knowledgeBases()) {
                for (Path document : context.dataset().documents(definition)) {
                    System.out.printf("[document][dry-run] upload kb=%s file=%s%n", definition.ref(), document);
                }
            }
            return;
        }
        ensureKnowledgeBaseRuntime(context);
        boolean replaceExisting = context.config().getBoolean("document.replace-existing", true);
        for (InitializerDataset.KnowledgeBaseDefinition definition : context.dataset().knowledgeBases()) {
            InitializerContext.KnowledgeBaseRuntime runtime = context.knowledgeBases().get(definition.ref());
            Map<String, List<Map<String, Object>>> existingByName = new HashMap<>();
            for (Map<String, Object> document : listDocuments(context, runtime.id())) {
                existingByName.computeIfAbsent(SimpleJson.string(document, "docName"), ignored -> new ArrayList<>())
                        .add(document);
            }
            for (Path file : context.dataset().documents(definition)) {
                String filename = file.getFileName().toString();
                List<Map<String, Object>> existing = existingByName.getOrDefault(filename, List.of());
                if (!existing.isEmpty() && !replaceExisting) {
                    Map<String, Object> document = existing.get(0);
                    if ("success".equalsIgnoreCase(SimpleJson.string(document, "status"))
                            && SimpleJson.integer(document, "chunkCount", 0) > 0) {
                        System.out.println("[document] 跳过已成功文档: " + filename);
                        continue;
                    }
                    throw new IllegalStateException("文档已存在且不可替换: " + filename);
                }
                for (Map<String, Object> document : existing) {
                    String status = SimpleJson.string(document, "status");
                    require(!"running".equalsIgnoreCase(status), "已有文档正在分块，无法替换: " + filename);
                    context.http().delete("/knowledge-base/docs/" +
                            RagentHttpClient.encodePath(SimpleJson.string(document, "id")));
                }

                System.out.printf("[document] 上传 kb=%s file=%s%n", definition.name(), filename);
                Map<String, Object> uploaded = SimpleJson.object(
                        context.http().uploadDocument(runtime.id(), file, definition.ingestionSpec()));
                String docId = SimpleJson.string(uploaded, "id");
                require(docId != null && !docId.isBlank(), "上传响应缺少文档 ID: " + filename);
                context.http().postEmpty("/knowledge-base/docs/" + RagentHttpClient.encodePath(docId) + "/chunk");
                waitForDocument(context, docId, filename);
            }
        }
    }

    static void initializeIntentTree(InitializerContext context) throws Exception {
        if (context.dryRun()) {
            for (InitializerDataset.IntentDefinition intent : context.dataset().intents()) {
                System.out.printf("[intent][dry-run] create code=%s parent=%s kbRef=%s%n",
                        intent.code(), intent.parentCode(), intent.knowledgeBaseRef());
            }
            return;
        }
        ensureKnowledgeBaseRuntime(context);
        List<Map<String, Object>> existing = flattenIntentTree(context.http().get("/intent-tree/trees"));
        if (!existing.isEmpty()) {
            List<String> ids = existing.stream().map(item -> SimpleJson.string(item, "id")).toList();
            context.http().postJson("/intent-tree/batch/delete", Map.of("ids", ids));
            System.out.println("[intent] 已清理旧意图节点: " + ids.size());
        }

        for (InitializerDataset.IntentDefinition intent : context.dataset().intents()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intentCode", intent.code());
            payload.put("name", intent.name());
            payload.put("level", intent.level());
            putIfNotNull(payload, "parentCode", intent.parentCode());
            putIfNotNull(payload, "description", intent.description());
            if (!intent.examples().isEmpty()) {
                payload.put("examples", intent.examples());
            }
            putIfNotNull(payload, "mcpToolId", intent.mcpToolId());
            payload.put("requireConfirm", intent.requireConfirm() ? 1 : 0);
            putIfNotNull(payload, "topK", intent.topK());
            payload.put("kind", intent.kind());
            payload.put("sortOrder", intent.sortOrder());
            payload.put("enabled", intent.enabled() ? 1 : 0);
            putIfNotNull(payload, "promptSnippet", intent.promptSnippet());
            putIfNotNull(payload, "promptTemplate", intent.promptTemplate());
            if (intent.knowledgeBaseRef() != null) {
                InitializerContext.KnowledgeBaseRuntime runtime =
                        context.knowledgeBases().get(intent.knowledgeBaseRef());
                payload.put("kbId", runtime.id());
                payload.put("collectionNames", List.of(runtime.collectionName()));
            }
            Object id = context.http().postJson("/intent-tree", payload);
            System.out.println("[intent] 已创建: " + intent.code() + " (" + id + ")");
        }
    }

    static void initializeSampleQuestions(InitializerContext context) throws Exception {
        List<InitializerDataset.QuestionDefinition> questions = context.dataset().questions();
        if (context.dryRun()) {
            for (InitializerDataset.QuestionDefinition question : questions) {
                System.out.printf("[sample-question][dry-run] create ref=%s title=%s%n",
                        question.ref(), question.title());
            }
            return;
        }
        List<Map<String, Object>> existing = fetchAllPages(context, "/sample-questions");
        for (Map<String, Object> item : existing) {
            context.http().delete("/sample-questions/" + RagentHttpClient.encodePath(SimpleJson.string(item, "id")));
        }
        if (!existing.isEmpty()) {
            System.out.println("[sample-question] 已清理旧示例问题: " + existing.size());
        }
        for (InitializerDataset.QuestionDefinition question : questions) {
            Map<String, Object> payload = new LinkedHashMap<>();
            putIfNotNull(payload, "title", question.title());
            putIfNotNull(payload, "description", question.description());
            payload.put("question", question.text());
            Object id = context.http().postJson("/sample-questions", payload);
            System.out.println("[sample-question] 已创建: " + question.ref() + " (" + id + ")");
        }
    }

    /**
     * 必须排在意图树之后：技能的 tool-ids 只能引用意图树里已启用的 MCP 节点，先播技能会被服务端直接拒掉
     */
    static void initializeSkills(InitializerContext context) throws Exception {
        List<InitializerDataset.SkillDefinition> skills = context.dataset().skills();
        if (skills.isEmpty()) {
            System.out.println("[agent-skill] 数据集没有配置技能手册，跳过");
            return;
        }
        if (context.dryRun()) {
            for (InitializerDataset.SkillDefinition skill : skills) {
                System.out.printf("[agent-skill][dry-run] create skillCode=%s tools=%s%n",
                        skill.skillCode(), skill.toolIds());
            }
            return;
        }
        List<Map<String, Object>> existing = fetchAllPages(context, "/agent-skills");
        for (Map<String, Object> item : existing) {
            context.http().delete("/agent-skills/" + RagentHttpClient.encodePath(SimpleJson.string(item, "id")));
        }
        if (!existing.isEmpty()) {
            System.out.println("[agent-skill] 已清理旧技能: " + existing.size());
        }
        for (InitializerDataset.SkillDefinition skill : skills) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("skillCode", skill.skillCode());
            payload.put("name", skill.name());
            putIfNotNull(payload, "description", skill.description());
            payload.put("content", skill.content());
            payload.put("toolIds", skill.toolIds());
            payload.put("sortOrder", skill.sortOrder());
            payload.put("enabled", skill.enabled());
            Object id = context.http().postJson("/agent-skills", payload);
            System.out.println("[agent-skill] 已创建: " + skill.skillCode() + " (" + id + ")");
        }
    }

    /**
     * 新建一份人设并激活，内置智能体一个字不动：切回去只要把 active 切回内置，空槽位自动回落
     * <p>
     * 必须排在 cleanup 之后——cleanup 按 builtin = 0 删行，先建的 profile 会被它删掉
     */
    static void initializeAgentProfile(InitializerContext context) throws Exception {
        InitializerDataset.AgentProfileDefinition profile = context.dataset().agentProfile();
        if (profile == null) {
            System.out.println("[agent-profile] 数据集没有配置人设，沿用内置智能体");
            return;
        }
        if (context.dryRun()) {
            System.out.printf("[agent-profile][dry-run] create name=%s slots=%s%n",
                    profile.name(), profile.prompts().keySet());
            return;
        }

        String agentId = findAgentIdByName(context, profile.name());
        if (agentId == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", profile.name());
            putIfNotNull(payload, "description", profile.description());
            putIfNotNull(payload, "avatar", profile.avatar());
            agentId = String.valueOf(context.http().postJson("/agents", payload));
            System.out.println("[agent-profile] 已创建: " + profile.name() + " (" + agentId + ")");
        } else {
            System.out.println("[agent-profile] 复用同名智能体: " + profile.name() + " (" + agentId + ")");
        }

        String encodedId = RagentHttpClient.encodePath(agentId);
        for (Map.Entry<String, String> slot : profile.prompts().entrySet()) {
            context.http().putJson("/agents/" + encodedId + "/prompts/" + RagentHttpClient.encodePath(slot.getKey()),
                    Map.of("content", slot.getValue()));
            System.out.println("[agent-profile] 已写入槽位: " + slot.getKey());
        }
        context.http().postEmpty("/agents/" + encodedId + "/activate");

        // 建了 profile 却一个槽位都没写成功时接口照样是 200，回读一次才看得出来
        verifyAgentPrompts(context, encodedId, profile);
        System.out.println("[agent-profile] 已激活并回读校验通过: " + profile.name());
    }

    private static String findAgentIdByName(InitializerContext context, String name) throws Exception {
        Map<String, Object> listing = SimpleJson.object(context.http().get("/agents"));
        for (Object item : SimpleJson.array(listing.get("agents"))) {
            Map<String, Object> agent = SimpleJson.object(item);
            if (name.equals(SimpleJson.string(agent, "name"))) {
                return SimpleJson.string(agent, "id");
            }
        }
        return null;
    }

    private static void verifyAgentPrompts(InitializerContext context, String encodedId,
                                           InitializerDataset.AgentProfileDefinition profile) throws Exception {
        Map<String, Object> config = SimpleJson.object(context.http().get("/agents/" + encodedId + "/prompts"));
        require(!Boolean.TRUE.equals(config.get("builtin")),
                "人设初始化写到了内置智能体上: " + profile.name());
        Map<String, String> actual = new LinkedHashMap<>();
        for (Object item : SimpleJson.array(config.get("slots"))) {
            Map<String, Object> slot = SimpleJson.object(item);
            actual.put(SimpleJson.string(slot, "slotKey"), SimpleJson.string(slot, "content"));
        }
        for (Map.Entry<String, String> expected : profile.prompts().entrySet()) {
            String content = actual.get(expected.getKey());
            require(content != null && !content.isBlank(),
                    "人设槽位写入后回读为空: " + expected.getKey());
            require(content.equals(expected.getValue()),
                    "人设槽位回读内容与数据集不一致: " + expected.getKey());
        }

        Map<String, Object> listing = SimpleJson.object(context.http().get("/agents"));
        boolean activated = false;
        for (Object item : SimpleJson.array(listing.get("agents"))) {
            Map<String, Object> agent = SimpleJson.object(item);
            if (profile.name().equals(SimpleJson.string(agent, "name"))) {
                activated = Boolean.TRUE.equals(agent.get("active"));
            }
        }
        require(activated, "人设已写入但没有激活: " + profile.name());
    }

    static void warmup(InitializerContext context) throws Exception {
        List<InitializerDataset.QuestionDefinition> questions = context.dataset().questions();
        if (questions.isEmpty()) {
            System.out.println("[warmup] 数据集没有配置演示问题，跳过");
            return;
        }
        if (context.skipWarmup()) {
            System.out.println("[warmup] 已按 --skip-warmup 跳过，问题数=" + questions.size());
            return;
        }
        if (context.dryRun()) {
            for (InitializerDataset.QuestionDefinition question : questions) {
                for (String text : question.turns()) {
                    System.out.printf("[warmup][dry-run] ask ref=%s question=%s%n", question.ref(), text);
                }
            }
            return;
        }
        long intervalMillis = Math.max(0L, context.config().getInt("warmup.interval-seconds", 3) * 1000L);
        WarmupRun run = new WarmupRun(context.config());
        List<InitializerDataset.QuestionDefinition> ordered = shuffleQuestions(context, questions);
        int totalTurns = ordered.stream().mapToInt(question -> question.turns().size()).sum();
        System.out.printf("[warmup] 串行提问 %d 个演示问题共 %d 轮，每题独立会话，单轮超时 %s，单轮最多尝试 %d 次%n",
                ordered.size(), totalTurns, run.timeout, run.attempts);

        int turn = 0;
        for (InitializerDataset.QuestionDefinition question : ordered) {
            List<String> texts = question.turns();
            String conversationId = null;
            for (int i = 0; i < texts.size(); i++) {
                if (turn > 0 && intervalMillis > 0) {
                    Thread.sleep(intervalMillis);
                }
                turn++;
                String label = turnLabel(question, i);
                System.out.printf("[warmup] (%d/%d) %s %s%n", turn, totalTurns, label, texts.get(i));
                conversationId = ask(context, run, label, texts.get(i), conversationId);
                if (conversationId == null) {
                    // 追问依赖本轮的会话和上下文，本轮放弃后同一问题剩下的轮次也没法问
                    for (int rest = i; rest < texts.size(); rest++) {
                        run.skippedTurns.add(turnLabel(question, rest));
                    }
                    break;
                }
            }
        }
        reportWarmup(run, ordered.size(), totalTurns);
    }

    private static String turnLabel(InitializerDataset.QuestionDefinition question, int index) {
        return index == 0 ? question.ref() : question.ref() + "-追问" + index;
    }

    /**
     * 预热只补对话数据，跳过的轮次不影响已经建好的知识库、文档和意图，因此汇报完照常按成功收尾
     */
    private static void reportWarmup(WarmupRun run, int questionCount, int totalTurns) {
        if (run.skippedTurns.isEmpty()) {
            System.out.printf("[warmup] 全部演示问题已跑通：问题 %d 个，共 %d 轮%n", questionCount, totalTurns);
            return;
        }
        if (!run.skippedTurns.isEmpty()) {
            System.out.printf("[warmup] 提问完成 %d/%d 轮，重试后仍失败的 %d 轮已跳过：%s%n",
                    totalTurns - run.skippedTurns.size(), totalTurns, run.skippedTurns.size(),
                    String.join("、", run.skippedTurns));
        }
        System.out.println("[warmup] 缺的只是对话数据，环境仍然可用，补齐单独重跑 WarmupMain 即可");
    }

    /**
     * 打乱提问顺序，避免初始化产生的会话列表与欢迎页示例问题一一对齐
     * 顺序影响会话时间线，配置固定 seed 可以复现某次运行
     */
    private static List<InitializerDataset.QuestionDefinition> shuffleQuestions(
            InitializerContext context, List<InitializerDataset.QuestionDefinition> questions) {
        String configured = context.config().get("warmup.shuffle-seed", null);
        long seed;
        if (configured == null || configured.isBlank()) {
            seed = ThreadLocalRandom.current().nextLong();
        } else {
            try {
                seed = Long.parseLong(configured);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("配置项不是整数: warmup.shuffle-seed=" + configured, ex);
            }
        }
        List<InitializerDataset.QuestionDefinition> ordered = new ArrayList<>(questions);
        Collections.shuffle(ordered, new Random(seed));
        System.out.println("[warmup] 提问顺序已随机，warmup.shuffle-seed=" + seed);
        return ordered;
    }

    /**
     * 提问一轮并补齐推荐追问，返回本轮所在会话，供同一问题的后续追问复用
     * 网络抖动、限流和模型偶发失败只影响这一轮，重试用尽后返回 null 表示放弃本轮
     */
    private static String ask(InitializerContext context, WarmupRun run, String label, String text,
                              String conversationId) throws InterruptedException {
        Instant started = Instant.now();
        RagentHttpClient.ChatStreamResult result = chat(context, run, label, text, conversationId);
        if (result == null) {
            return null;
        }
        System.out.printf("[warmup] %s 完成：conversationId=%s, 回答 %d 字, 耗时 %d 秒%n",
                label, result.conversationId(), result.answerLength(),
                Duration.between(started, Instant.now()).toSeconds());
        return result.conversationId();
    }

    /**
     * 重试沿用同一个 conversationId，首轮为空则每次尝试都新开会话，失败的那次会在会话列表里留下半截记录
     */
    private static RagentHttpClient.ChatStreamResult chat(InitializerContext context, WarmupRun run, String label,
                                                          String text, String conversationId)
            throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return context.http().chatStream(text, conversationId, run.timeout);
            } catch (InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                if (attempt >= run.attempts) {
                    System.out.printf("[warmup] %s 连续 %d 次提问失败，跳过本轮：%s%n", label, attempt, ex.getMessage());
                    return null;
                }
                System.out.printf("[warmup] %s 第 %d 次提问失败，%d 秒后重试：%s%n",
                        label, attempt, run.retryMillis / 1000, ex.getMessage());
                Thread.sleep(run.retryMillis);
            }
        }
    }

    /**
     * 一次预热的执行参数与跳过记录
     */
    private static final class WarmupRun {

        private final Duration timeout;
        private final int attempts;
        private final long retryMillis;
        private final List<String> skippedTurns = new ArrayList<>();

        private WarmupRun(InitializerConfig config) {
            this.timeout = Duration.ofSeconds(config.getInt("warmup.timeout-seconds", 600));
            // 含首次调用在内的总尝试次数，配成 1 就是不重试
            this.attempts = Math.max(1, config.getInt("warmup.max-attempts", 3));
            this.retryMillis = Math.max(0L, config.getInt("warmup.retry-interval-seconds", 10) * 1000L);
        }
    }

    static void verify(InitializerContext context) throws Exception {
        if (context.dryRun()) {
            System.out.println("[verify][dry-run] 跳过远端结果校验");
            return;
        }
        Map<String, Map<String, Object>> baseByCollection = new HashMap<>();
        for (Map<String, Object> base : listKnowledgeBases(context)) {
            baseByCollection.put(SimpleJson.string(base, "collectionName"), base);
        }
        require(baseByCollection.size() == context.config().getInt("verification.knowledge-base-count",
                        context.dataset().knowledgeBases().size()),
                "知识库总数校验失败，actual=" + baseByCollection.size());

        int documentCount = 0;
        Set<String> allDocumentNames = new HashSet<>();
        for (InitializerDataset.KnowledgeBaseDefinition definition : context.dataset().knowledgeBases()) {
            Map<String, Object> base = baseByCollection.get(definition.collectionName());
            require(base != null, "缺少知识库: " + definition.collectionName());
            List<Map<String, Object>> documents = listDocuments(context, SimpleJson.string(base, "id"));
            Set<String> expectedNames = new HashSet<>();
            for (Path path : context.dataset().documents(definition)) {
                expectedNames.add(path.getFileName().toString());
            }
            allDocumentNames.addAll(expectedNames);
            require(documents.size() == expectedNames.size(),
                    "知识库文档数量不一致: " + definition.name() + ", expected=" + expectedNames.size()
                            + ", actual=" + documents.size());
            for (Map<String, Object> document : documents) {
                String name = SimpleJson.string(document, "docName");
                require(expectedNames.contains(name), "出现当前智能体类型外的文档: " + name);
                require("success".equalsIgnoreCase(SimpleJson.string(document, "status")),
                        "文档未成功: " + name);
                require(SimpleJson.integer(document, "chunkCount", 0) > 0,
                        "文档没有 Chunk: " + name);
            }
            documentCount += documents.size();
        }
        require(documentCount == context.config().getInt("verification.document-count",
                        context.dataset().documentCount()),
                "文档总数校验失败，actual=" + documentCount);

        List<Map<String, Object>> intents = flattenIntentTree(context.http().get("/intent-tree/trees"));
        require(intents.size() == context.config().getInt("verification.intent-count", context.dataset().intents().size()),
                "意图节点总数校验失败，actual=" + intents.size());
        Map<String, Map<String, Object>> intentByCode = new HashMap<>();
        intents.forEach(intent -> intentByCode.put(SimpleJson.string(intent, "intentCode"), intent));
        for (InitializerDataset.IntentDefinition definition : context.dataset().intents()) {
            Map<String, Object> actual = intentByCode.get(definition.code());
            require(actual != null, "缺少意图节点: " + definition.code());
            if (definition.knowledgeBaseRef() != null) {
                String expectedCollection = context.dataset().knowledgeBase(definition.knowledgeBaseRef()).collectionName();
                List<Object> collections = SimpleJson.array(actual.get("collectionNames"));
                require(collections.stream().map(String::valueOf).anyMatch(expectedCollection::equals),
                        "意图没有绑定预期知识库: " + definition.code());
            }
        }
        List<Map<String, Object>> questions = fetchAllPages(context, "/sample-questions");
        require(questions.size() == context.config().getInt("verification.question-count",
                        context.dataset().questions().size()),
                "示例问题总数校验失败，actual=" + questions.size());
        Set<String> actualQuestionTexts = new HashSet<>();
        questions.forEach(question -> actualQuestionTexts.add(SimpleJson.string(question, "question")));
        for (InitializerDataset.QuestionDefinition definition : context.dataset().questions()) {
            require(actualQuestionTexts.contains(definition.text()), "缺少示例问题: " + definition.ref());
        }

        List<Map<String, Object>> skills = fetchAllPages(context, "/agent-skills");
        require(skills.size() == context.config().getInt("verification.skill-count",
                        context.dataset().skills().size()),
                "技能总数校验失败，actual=" + skills.size());
        Map<String, Map<String, Object>> skillByCode = new HashMap<>();
        skills.forEach(skill -> skillByCode.put(SimpleJson.string(skill, "skillCode"), skill));
        for (InitializerDataset.SkillDefinition definition : context.dataset().skills()) {
            Map<String, Object> actual = skillByCode.get(definition.skillCode());
            require(actual != null, "缺少技能: " + definition.skillCode());
            List<Object> toolIds = SimpleJson.array(actual.get("toolIds"));
            require(toolIds.size() == definition.toolIds().size(),
                    "技能解锁的工具数量不一致: " + definition.skillCode());
            for (String toolId : definition.toolIds()) {
                require(toolIds.stream().map(String::valueOf).anyMatch(toolId::equals),
                        "技能没有解锁预期工具: " + definition.skillCode() + " -> " + toolId);
            }
        }

        verifyAgentProfile(context);
        verifyBizDatabase(context, allDocumentNames);

        System.out.printf("[verify] 通过：knowledgeBases=%d, documents=%d, intents=%d, questions=%d, skills=%d%n",
                baseByCollection.size(), documentCount, intents.size(), questions.size(), skills.size());
    }

    /**
     * 当前激活的必须是数据集声明的那份人设，且槽位内容与数据集一致
     * <p>
     * 写入那一步已经回读校验过一次，这里重来是因为中间还隔着几步：cleanup 删过非内置人设，
     * 手工按单步入口跑的时候顺序很容易错开
     */
    private static void verifyAgentProfile(InitializerContext context) throws Exception {
        InitializerDataset.AgentProfileDefinition profile = context.dataset().agentProfile();
        if (profile == null) {
            return;
        }
        String agentId = findAgentIdByName(context, profile.name());
        require(agentId != null, "数据集声明的人设不存在: " + profile.name());
        verifyAgentPrompts(context, RagentHttpClient.encodePath(agentId), profile);
        System.out.println("[verify] 人设已激活: " + profile.name());
    }

    /**
     * 业务库行数，外加这套数据集唯一的跨库约束：库里的每款设备，知识库里都要有同名详情文档
     * <p>
     * 少了这条约束，商品工具筛出来的款会有查不到详情的，而两侧各自都是「对」的。
     * 配件不参与这条：它们按二级品类合写导购篇，一件一篇既写不完也会把检索池灌满近似文档
     */
    private static void verifyBizDatabase(InitializerContext context, Set<String> documentNames) throws Exception {
        if (!context.hasBizDatabase()) {
            return;
        }
        long spus = context.bizJdbc().queryLong("SELECT count(*) FROM t_product");
        int expectedSpus = context.config().requireInt("verification.biz-spu-count");
        require(spus == expectedSpus,
                "业务库商品款行数校验失败，expected=" + expectedSpus + ", actual=" + spus);

        long skus = context.bizJdbc().queryLong("SELECT count(*) FROM t_product_sku");
        int expectedSkus = context.config().requireInt("verification.biz-sku-count");
        require(skus == expectedSkus,
                "业务库商品配置行数校验失败，expected=" + expectedSkus + ", actual=" + skus);

        List<List<String>> deviceRows = context.bizJdbc()
                .queryRows("SELECT spu_code FROM t_product WHERE category <> '配件' ORDER BY spu_code");
        for (List<String> row : deviceRows) {
            require(documentNames.contains(row.get(0) + ".md"),
                    "业务库里的设备在知识库里没有详情文档: " + row.get(0));
        }

        long orphans = context.bizJdbc()
                .queryLong("SELECT count(*) FROM t_product_sku s"
                        + " LEFT JOIN t_product p ON p.spu_code = s.spu_code WHERE p.spu_code IS NULL");
        require(orphans == 0, "存在挂不到商品款上的配置行，count=" + orphans);

        long orders = context.bizJdbc().queryLong("SELECT count(*) FROM t_order");
        int expectedOrders = context.config().requireInt("verification.biz-order-count");
        require(orders == expectedOrders,
                "业务库订单行数校验失败，expected=" + expectedOrders + ", actual=" + orders);

        long coupons = context.bizJdbc().queryLong("SELECT count(*) FROM t_coupon");
        int expectedCoupons = context.config().requireInt("verification.biz-coupon-count");
        require(coupons == expectedCoupons,
                "业务库券模板行数校验失败，expected=" + expectedCoupons + ", actual=" + coupons);

        System.out.printf("[verify] 业务库通过：spus=%d, skus=%d, orders=%d, coupons=%d%n",
                spus, skus, orders, coupons);
    }

    private static void deleteAllDocuments(InitializerContext context) throws Exception {
        for (Map<String, Object> base : listKnowledgeBases(context)) {
            String kbId = SimpleJson.string(base, "id");
            for (Map<String, Object> document : listDocuments(context, kbId)) {
                String status = SimpleJson.string(document, "status");
                String name = SimpleJson.string(document, "docName");
                require(!"running".equalsIgnoreCase(status), "文档正在分块，拒绝清理: " + name);
                String docId = SimpleJson.string(document, "id");
                context.http().delete("/knowledge-base/docs/" + RagentHttpClient.encodePath(docId));
                System.out.println("[cleanup] 已删除文档: " + name + " (" + docId + ")");
            }
        }
    }

    private static void clearRedis(InitializerContext context) throws Exception {
        int exact = context.redis().deleteExact(context.config().getList("redis.cleanup-exact-keys"));
        int pattern = 0;
        for (String keyPattern : context.config().getList("redis.cleanup-patterns")) {
            if (INITIALIZER_LOCK_KEY.equals(keyPattern)) {
                throw new IllegalArgumentException("禁止清理初始化锁 Key");
            }
            pattern += context.redis().deleteByPattern(keyPattern);
        }
        System.out.printf("[cleanup] Redis 删除 exact=%d, pattern=%d%n", exact, pattern);
    }

    private static void assertIdle(InitializerContext context) throws Exception {
        if (!context.config().getBoolean("execution.require-idle", true)) {
            return;
        }
        int minutes = context.config().getInt("execution.active-window-minutes", 30);
        String interval = minutes + " minutes";
        String sql = "SELECT "
                + "(SELECT COUNT(*) FROM t_knowledge_document WHERE lower(status)='running' "
                + "AND update_time >= NOW() - INTERVAL '" + interval + "') + "
                + "(SELECT COUNT(*) FROM t_rag_trace_run WHERE upper(status)='RUNNING' "
                + "AND update_time >= NOW() - INTERVAL '" + interval + "') + "
                + "(SELECT COUNT(*) FROM t_ingestion_task WHERE lower(status)='running' "
                + "AND update_time >= NOW() - INTERVAL '" + interval + "')";
        long runningRows = context.jdbc().queryLong(sql);
        int activeRedis = context.redis().scan("ragent:agent:running:*").size()
                + context.redis().scan("ragent:agent:run-lock:*").size()
                + context.redis().scan("ragent:agent:run-permit:*").size()
                + context.redis().scan("ragent:stream:owner:*").size();
        require(runningRows == 0 && activeRedis == 0,
                "检测到运行中的任务，拒绝初始化: db=" + runningRows + ", redis=" + activeRedis);
    }

    private static void verifyBackendSettings(InitializerContext context, Map<String, Object> settings) {
        Map<String, Object> backends = SimpleJson.object(settings.get("backends"));
        verifyBackend(context, backends, "vector", "execution.expected-vector-type");
        verifyBackend(context, backends, "storage", "execution.expected-storage-type");
        verifyBackend(context, backends, "keyword", "execution.expected-keyword-type");
        verifyBackend(context, backends, "graph", "execution.expected-graph-type");
    }

    private static void verifyBackend(InitializerContext context, Map<String, Object> backends,
                                      String backendName, String configKey) {
        String expected = context.config().get(configKey, "");
        if (expected.isBlank()) {
            return;
        }
        Map<String, Object> backend = SimpleJson.object(backends.get(backendName));
        String actual = SimpleJson.string(backend, "type");
        require(expected.equalsIgnoreCase(actual),
                "运行中后端与初始化配置不一致: " + backendName + " expected=" + expected + ", actual=" + actual);
    }

    private static void waitForDocument(InitializerContext context, String docId, String filename) throws Exception {
        Duration timeout = Duration.ofSeconds(context.config().getInt("document.chunk-timeout-seconds", 1200));
        Duration interval = Duration.ofSeconds(context.config().getInt("document.poll-interval-seconds", 3));
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> document = SimpleJson.object(context.http().get(
                    "/knowledge-base/docs/" + RagentHttpClient.encodePath(docId)));
            String status = SimpleJson.string(document, "status");
            if ("success".equalsIgnoreCase(status)) {
                int chunks = SimpleJson.integer(document, "chunkCount", 0);
                require(chunks > 0, "文档分块成功但 Chunk 数为 0: " + filename);
                System.out.println("[document] 分块成功: " + filename + ", chunks=" + chunks);
                return;
            }
            if ("failed".equalsIgnoreCase(status)) {
                throw new IllegalStateException("文档分块失败: " + filename + latestChunkError(context, docId));
            }
            Thread.sleep(Math.max(250L, interval.toMillis()));
        }
        throw new IllegalStateException("等待文档分块超时: " + filename + ", timeout=" + timeout);
    }

    private static String latestChunkError(InitializerContext context, String docId) {
        try {
            Object pageValue = context.http().get("/knowledge-base/docs/" + RagentHttpClient.encodePath(docId)
                    + "/chunk-logs?current=1&size=1");
            List<Map<String, Object>> records = pageRecords(pageValue);
            if (records.isEmpty()) {
                return "";
            }
            String error = SimpleJson.string(records.get(0), "errorMessage");
            return error == null || error.isBlank() ? "" : "，error=" + error;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void ensureKnowledgeBaseRuntime(InitializerContext context) throws Exception {
        if (context.knowledgeBases().size() == context.dataset().knowledgeBases().size()) {
            return;
        }
        Map<String, Map<String, Object>> byCollection = new HashMap<>();
        for (Map<String, Object> base : listKnowledgeBases(context)) {
            byCollection.put(SimpleJson.string(base, "collectionName"), base);
        }
        for (InitializerDataset.KnowledgeBaseDefinition definition : context.dataset().knowledgeBases()) {
            Map<String, Object> base = byCollection.get(definition.collectionName());
            require(base != null, "请先初始化知识库: " + definition.name());
            context.knowledgeBases().put(definition.ref(), new InitializerContext.KnowledgeBaseRuntime(
                    SimpleJson.string(base, "id"), definition.collectionName(), definition.name()));
        }
    }

    private static List<Map<String, Object>> listKnowledgeBases(InitializerContext context) throws Exception {
        return fetchAllPages(context, "/knowledge-base");
    }

    private static List<Map<String, Object>> listDocuments(InitializerContext context, String kbId) throws Exception {
        return fetchAllPages(context, "/knowledge-base/" + RagentHttpClient.encodePath(kbId) + "/docs");
    }

    private static List<Map<String, Object>> fetchAllPages(InitializerContext context, String path) throws Exception {
        int current = 1;
        int size = 500;
        List<Map<String, Object>> all = new ArrayList<>();
        while (true) {
            String separator = path.contains("?") ? "&" : "?";
            Object pageValue = context.http().get(path + separator + "current=" + current + "&size=" + size);
            Map<String, Object> page = SimpleJson.object(pageValue);
            for (Object item : SimpleJson.array(page.get("records"))) {
                all.add(SimpleJson.object(item));
            }
            int pages = SimpleJson.integer(page, "pages", 1);
            if (current >= pages) {
                return all;
            }
            current++;
        }
    }

    private static List<Map<String, Object>> pageRecords(Object pageValue) {
        Map<String, Object> page = SimpleJson.object(pageValue);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : SimpleJson.array(page.get("records"))) {
            records.add(SimpleJson.object(item));
        }
        return records;
    }

    private static List<Map<String, Object>> flattenIntentTree(Object treeValue) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object root : SimpleJson.array(treeValue)) {
            flattenIntent(SimpleJson.object(root), result);
        }
        result.sort(Comparator.comparingInt(item -> SimpleJson.integer(item, "sortOrder", 0)));
        return result;
    }

    private static void flattenIntent(Map<String, Object> node, List<Map<String, Object>> result) {
        result.add(node);
        Object children = node.get("children");
        if (children == null) {
            return;
        }
        for (Object child : SimpleJson.array(children)) {
            flattenIntent(SimpleJson.object(child), result);
        }
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
