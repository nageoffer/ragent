/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.agent.memory;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.AgentEngineConfiguration;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 记忆摘要回归的离线重放：拿实跑存下的压缩前上下文，换上仓库里的压缩提示词，走生产压缩器逐代重压
 * 由 resources/regression/agent-compaction/run.sh --replay 驱动，产物交回回归台判定，普通单测不触发
 * 切点、素材渲染、模型与预算全走生产代码和 application.yaml，只有提示词改读文件、落库换成假的
 */
@EnabledIfEnvironmentVariable(named = "RAGENT_COMPACTION_REPLAY_SOURCE", matches = ".+")
class AgentCompactionReplayLiveTest {

    private static final JsonCodec CODEC = JsonUtils.getJsonCodec();

    /**
     * 实跑里摘要没压成，生产侧下一轮会再试；重放没有下一轮，就地重试
     */
    private static final int MAX_ATTEMPTS = 3;

    @Test
    void replay() throws Exception {
        Path source = Path.of(env("RAGENT_COMPACTION_REPLAY_SOURCE"));
        Path target = Path.of(env("RAGENT_COMPACTION_REPLAY_TARGET"));
        Path suite = Path.of(env("RAGENT_COMPACTION_REPLAY_SUITE"));
        int repeat = Integer.parseInt(System.getenv().getOrDefault("RAGENT_COMPACTION_REPLAY_REPEAT", "1"));

        Properties regression = properties(suite.resolve("regression.properties"));
        String prompt = Files.readString(compactionPromptFile(suite, regression), StandardCharsets.UTF_8);
        Binder binder = binder(suite.resolve(regression.getProperty("application.config")).normalize());
        AgentProperties agent = binder.bind("agent", AgentProperties.class).orElseGet(AgentProperties::new);
        AIModelProperties ai = binder.bind("ai", AIModelProperties.class).orElseGet(AIModelProperties::new);
        AgentMemoryProperties memory = binder.bind("agent.memory", AgentMemoryProperties.class)
                .orElseGet(AgentMemoryProperties::new);
        AIModelProperties.ProviderConfig provider = ai.getProviders().get(agent.getChat().getProvider());
        assertThat(provider == null ? null : provider.getApiKey())
                .as("agent.chat 指向的供应商 %s 没有 api-key：先 export application.yaml 里它引用的那个环境变量",
                        agent.getChat().getProvider())
                .isNotBlank();
        Model model = new AgentEngineConfiguration(agent, ai).agentChatModel();
        AgentPromptResolver resolver = mock(AgentPromptResolver.class, CALLS_REAL_METHODS);
        doReturn(prompt).when(resolver).resolve(AgentPromptSlot.AGENT_CONTEXT_COMPACTION);

        List<Path> sessions;
        try (Stream<Path> stream = Files.list(source)) {
            sessions = stream.filter(path -> path.getFileName().toString().startsWith("session-")
                    && hasInput(path)).sorted().toList();
        }
        assertThat(sessions).as("%s 下没有带压缩前快照的会话，重放只认存了 gen-K.input.json 的实跑产物", source)
                .isNotEmpty();
        Files.createDirectories(target);

        ExecutorService pool = Executors.newFixedThreadPool(sessions.size() * repeat);
        Map<String, Future<String>> chains = new LinkedHashMap<>();
        for (Path session : sessions) {
            for (int round = 1; round <= repeat; round++) {
                String name = session.getFileName() + (round == 1 ? "" : "-r" + round);
                Chain chain = new Chain(session, target.resolve(name), model, agent, resolver, memory);
                chains.put(name, pool.submit(chain::run));
            }
        }
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, Future<String>> entry : chains.entrySet()) {
            String error = entry.getValue().get();
            if (error != null) {
                broken.add(entry.getKey() + "：" + error);
            }
        }
        pool.shutdown();

        Map<String, Object> facts = map(source.resolve("facts.json"));
        facts.put("compactionPromptMd5", DigestUtils.md5DigestAsHex(prompt.getBytes(StandardCharsets.UTF_8)));
        facts.put("compactionPromptChars", prompt.length());
        facts.put("newWrites", -1);
        facts.put("replayOf", source.toString());
        Files.writeString(target.resolve("facts.json"), CODEC.toJson(facts), StandardCharsets.UTF_8);
        assertThat(broken).as("这些会话没重放完，报告里它们的代数会偏少").isEmpty();
    }

    /**
     * 一条会话按代串着重压：第 K 代的上一代摘要用重放出来的那份，对话原文沿用实跑
     * 从第一份有快照的那代起压，它的上一代摘要只能用实跑的；素材字数必须与实跑一致，否则快照不是那一刻的上下文
     */
    private static final class Chain {

        private final Path session;
        private final Path out;
        private final RecordingSummarizer summarizer;
        private final AgentContextCompactor compactor;

        Chain(Path session, Path out, Model model, AgentProperties agent, AgentPromptResolver resolver,
              AgentMemoryProperties memory) {
            this.session = session;
            this.out = out;
            this.summarizer = new RecordingSummarizer(model, agent, resolver, memory);
            this.compactor = new AgentContextCompactor(summarizer, memory, mock(AgentContextCompactionMapper.class));
        }

        String run() throws IOException {
            Files.createDirectories(out);
            Files.copy(session.resolve("turns.jsonl"), out.resolve("turns.jsonl"), StandardCopyOption.REPLACE_EXISTING);
            String liveSummary = null;
            String replayedSummary = null;
            for (int number = 1; Files.exists(session.resolve("gen-" + number + ".json")); number++) {
                Path inputFile = session.resolve("gen-" + number + ".input.json");
                if (!Files.exists(inputFile)) {
                    if (replayedSummary == null) {
                        continue;
                    }
                    return "第 " + number + " 代没有压缩前快照，从这一代起接不上";
                }
                Map<String, Object> live = map(session.resolve("gen-" + number + ".json"));
                Map<String, Object> input = map(inputFile);
                List<Msg> messages = new ArrayList<>();
                for (Object item : (List<?>) input.get("context")) {
                    messages.add(CODEC.convertValue(item, Msg.class));
                }
                messages.add(Msg.builder().name("user").role(MsgRole.USER)
                        .textContent(String.valueOf(input.get("question"))).build());
                if (replayedSummary != null) {
                    Msg summary = messages.get(0);
                    String text = summary.getTextContent();
                    if (text == null || !text.endsWith(liveSummary)) {
                        return "第 " + number + " 代快照的首条消息不是实跑上一代的摘要";
                    }
                    messages.set(0, Msg.builder().id(summary.getId()).name(summary.getName()).role(summary.getRole())
                            .timestamp(summary.getTimestamp()).metadata(summary.getMetadata())
                            .textContent(text.substring(0, text.length() - liveSummary.length()) + replayedSummary)
                            .build());
                }

                int before = AgentContextChars.total(messages);
                List<Msg> compacted = null;
                int failures = 0;
                while (compacted == null && failures < MAX_ATTEMPTS) {
                    List<Msg> working = new ArrayList<>(messages);
                    if (compactor.compactInPlace(working, "replay", session.getFileName().toString())) {
                        compacted = working;
                    } else {
                        failures++;
                    }
                }
                if (compacted == null) {
                    return "第 " + number + " 代连续 " + MAX_ATTEMPTS + " 次没压成（模型失败、摘要为空或超长截断），从这一代起接不上";
                }
                int liveMaterial = ((Number) live.get("materialChars")).intValue();
                if (summarizer.materialChars != liveMaterial) {
                    return "第 " + number + " 代素材 " + summarizer.materialChars + " 字，实跑是 " + liveMaterial
                            + " 字：快照和实跑那一刻的上下文对不上";
                }

                Map<String, Object> gen = new LinkedHashMap<>();
                gen.put("number", number);
                gen.put("turn", live.get("turn"));
                gen.put("compactedThrough", live.get("compactedThrough"));
                gen.put("summaryChars", summarizer.summary.length());
                gen.put("materialChars", summarizer.materialChars);
                gen.put("before", before);
                gen.put("after", AgentContextChars.total(compacted));
                gen.put("liveSummaryChars", live.get("summaryChars"));
                gen.put("failedAttempts", failures);
                gen.put("summary", summarizer.summary);
                Files.writeString(out.resolve("gen-" + number + ".json"), CODEC.toJson(gen), StandardCharsets.UTF_8);
                Files.writeString(out.resolve("gen-" + number + ".md"), summarizer.summary, StandardCharsets.UTF_8);
                System.out.println("[replay] " + out.getFileName() + " 第 " + number + " 代：" + summarizer.summary.length()
                        + " 字（实跑 " + live.get("summaryChars") + " 字）" + (failures == 0 ? "" : "，失败重试 " + failures + " 次"));
                liveSummary = String.valueOf(live.get("summary"));
                replayedSummary = summarizer.summary;
            }
            return null;
        }
    }

    /**
     * 记下生产压缩器交给摘要器的素材与产出，落库那一步在重放里是假的
     */
    private static final class RecordingSummarizer extends AgentConversationSummarizer {

        private int materialChars;
        private String summary;

        RecordingSummarizer(Model model, AgentProperties agent, AgentPromptResolver resolver,
                            AgentMemoryProperties memory) {
            super(model, agent, resolver, memory);
        }

        @Override
        public String summarize(List<Msg> material, String existingSummary) {
            materialChars = AgentContextChars.total(material);
            summary = super.summarize(material, existingSummary);
            return summary;
        }
    }

    private static boolean hasInput(Path session) {
        try (Stream<Path> files = Files.list(session)) {
            return files.anyMatch(file -> file.getFileName().toString().matches("gen-\\d+\\.input\\.json"));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 与应用同一份 application.yaml，占位符照样从环境变量取
     */
    private static Binder binder(Path yaml) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new FileSystemResource(yaml))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment);
    }

    /**
     * 与回归台预检同一条路：regression.properties 指到数据集，数据集清单指到压缩槽位的文件
     */
    private static Path compactionPromptFile(Path suite, Properties regression) throws IOException {
        Path promptsDir = suite.resolve(regression.getProperty("bit.prompts-dir")).normalize();
        Properties manifest = properties(promptsDir.resolveSibling("agent-profile.properties"));
        String file = manifest.getProperty("prompt." + AgentPromptSlot.AGENT_CONTEXT_COMPACTION.name());
        assertThat(file).as("数据集没有覆盖压缩槽位，被测的不是比特严选那份压缩提示词").isNotBlank();
        return promptsDir.resolveSibling(file.trim());
    }

    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Path file) throws IOException {
        return new LinkedHashMap<>(CODEC.fromJson(Files.readString(file, StandardCharsets.UTF_8), Map.class));
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertThat(StrUtil.isNotBlank(value)).as("缺少环境变量 %s", name).isTrue();
        return value;
    }
}
