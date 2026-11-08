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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryCursorMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.api.RLock;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实 PostgreSQL 上的批次机制：门槛按用户跨会话累计，A→B→A 的积压是一批、一次仲裁
 * 模型与锁是桩，只验取数、排序、会话编号与游标；语义交给同级仓 ragenteval 的 agent/agent_eval/functional/long_term_memory 在线回归
 * RAGENT_LIVE_MEMORY_TEST=true 时运行，数据库取当前 application.yaml，测试用户收尾删除
 */
@EnabledIfEnvironmentVariable(named = "RAGENT_LIVE_MEMORY_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentMemoryDatabaseTest {

    private final List<String> users = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private AgentMemoryRepository repository;
    private JdbcTemplate jdbc;
    private AgentMemoryProperties memory;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {
    }

    @BeforeAll
    void setUp() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application",
                new FileSystemResource(root().resolve("bootstrap/src/main/resources/application.yaml")))) {
            environment.getPropertySources().addLast(source);
        }
        memory = new AgentMemoryProperties();
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        source.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        source.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        jdbc = new JdbcTemplate(source);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = GlobalConfigUtils.defaults().setMetaObjectHandler(new MyMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.addMapper(AgentMemoryMapper.class);
        configuration.addMapper(AgentMemoryCursorMapper.class);
        configuration.addMapper(AgentMessageMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(source));
        context.registerBean(AgentMemoryRepository.class, () -> new AgentMemoryRepository(
                session.getMapper(AgentMemoryMapper.class), session.getMapper(AgentMemoryCursorMapper.class),
                session.getMapper(AgentMessageMapper.class), memory));
        context.refresh();
        repository = context.getBean(AgentMemoryRepository.class);
    }

    @AfterAll
    void cleanUp() {
        for (String user : users) {
            for (String table : List.of("t_agent_memory", "t_agent_message", "t_agent_memory_cursor")) {
                jdbc.update("delete from " + table + " where user_id = ?", user);
            }
        }
        if (context != null) context.close();
    }

    /**
     * 主动整理只看本轮请求及同会话背景：别的对话里的积压不进材料，后台游标不动；新行带上请求消息 id 与表达时间
     */
    @Test
    void flushShouldHandleOnlyTheRequestAndRecordItsSource() {
        Seed seed = seed(List.of(new Step("A", "user", "我一直喜欢大屏"), new Step("T", "assistant", "您平时戴眼镜吗？"),
                new Step("T", "user", "对，一直戴，记住")));
        Model model = answering("{\"decisions\":[{\"action\":\"ADD\",\"content\":\"用户戴眼镜\",\"sourceMessageId\":\""
                + seed.lastUserMessage + "\"}],\"explanation\":\"结合助手的问题确认了戴眼镜\"}");

        AgentMemoryOutcome outcome = flusher(model).flush(seed.user, seed.lastUserMessage);

        assertThat(outcome.status()).isEqualTo(AgentMemoryOutcome.Status.COMMITTED);
        assertThat(outcome.changes()).containsExactly("新增：用户戴眼镜");
        assertThat(cursor(seed.user)).isNull();
        String material = judgeInput(model).get(1);
        assertThat(material).contains("您平时戴眼镜吗？", "对，一直戴，记住").doesNotContain("我一直喜欢大屏");
        // 种子消息走库默认时间带微秒，Date 只到毫秒；生产链路的消息时间由应用按毫秒写入
        Map<String, Object> row = jdbc.queryForMap("select m.source_message_id,"
                + " m.expressed_at = date_trunc('milliseconds', u.create_time) as same_time"
                + " from t_agent_memory m join t_agent_message u on u.id = m.source_message_id where m.user_id = ?", seed.user);
        assertThat(row.get("source_message_id")).isEqualTo(seed.lastUserMessage);
        assertThat(row.get("same_time")).isEqualTo(true);
    }

    /**
     * 后台门槛数的是用户跨会话的待处理条数：两条不抽，第三条一到就三条一起抽
     */
    @Test
    void backgroundShouldWaitForThreeAcrossConversationsThenTakeThemAll() throws Exception {
        Seed seed = seed(List.of(new Step("A", "user", "一"), new Step("B", "user", "二")));
        assertThat(learner(answering(EMPTY)).learn(seed.user).status())
                .isEqualTo(AgentMemoryOutcome.Status.BELOW_THRESHOLD);
        assertThat(cursor(seed.user)).isNull();

        String third = insert(seed.user, seed.conversations.get("A"), "user", "三");
        AgentMemoryOutcome outcome = learner(answering(EMPTY)).learn(seed.user);

        assertThat(outcome.status()).isEqualTo(AgentMemoryOutcome.Status.COMMITTED);
        assertThat(cursor(seed.user)).isEqualTo(third);
    }

    /**
     * 游标初值是建行那一刻已有的最后一条用户发言，之后再调不动；没有发言时为 NULL
     */
    @Test
    void ensureCursorShouldStopAtExistingMessagesAndNeverMove() {
        String user = IdWorker.getIdStr();
        users.add(user);
        String conversation = IdWorker.getIdStr();
        String history = insert(user, conversation, "user", "接入前的旧发言");

        repository.ensureCursor(user);
        insert(user, conversation, "user", "接入后的第一句");
        repository.ensureCursor(user);

        assertThat(cursor(user)).isEqualTo(history);
        assertThat(repository.loadUserMessagesAfter(user, cursor(user), 40)).extracting(AgentMessageDO::getContent)
                .containsExactly("接入后的第一句");
    }

    /**
     * 游标推进和记忆修改在同一个事务里：后面一步落空，前面已执行的删除和游标推进一起回滚
     */
    @Test
    void failedWriteShouldRollBackCursorAndEarlierChanges() {
        Seed seed = seed(List.of(new Step("A", "user", "忘掉我住杭州")));
        String memoryId = IdWorker.getIdStr();
        jdbc.update("insert into t_agent_memory (id,user_id,content,source_type) values (?,?,?,?)",
                memoryId, seed.user, "用户住在杭州", "FLUSH");
        AgentMemoryEdit edit = new AgentMemoryEdit(List.of(memoryId, "missing-id"), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> repository.commitBackground(seed.user, null, seed.lastUserMessage, edit))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cursor(seed.user)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from t_agent_memory where user_id=? and invalid_at is null",
                Integer.class, seed.user)).isOne();
    }

    private static final String EMPTY = "{\"decisions\":[],\"explanation\":\"没有需要长期记住的内容\"}";

    private AgentMemoryFlusher flusher(Model model) {
        return new AgentMemoryFlusher(grantingLock(), repository, judge(model), memory);
    }

    private AgentMemoryLearner learner(Model model) {
        return new AgentMemoryLearner(grantingLock(), repository, judge(model), memory);
    }

    private AgentMemoryJudge judge(Model model) {
        AgentPromptResolver resolver = mock(AgentPromptResolver.class);
        when(resolver.render(any(), anyMap())).thenReturn("抽取提示词");
        return new AgentMemoryJudge(model, new AgentProperties(), resolver, memory, Jackson2ObjectMapperBuilder.json().build());
    }

    private AgentMemoryLock grantingLock() {
        AgentMemoryLock lock = mock(AgentMemoryLock.class);
        when(lock.tryAcquire(anyString())).thenReturn(mock(RLock.class));
        return lock;
    }

    private Model answering(String raw) {
        Model model = mock(Model.class);
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(ChatResponse.builder().finishReason("stop")
                .content(List.of(TextBlock.builder().text(raw).build())).build()));
        return model;
    }

    @SuppressWarnings("unchecked")
    private List<String> judgeInput(Model model) {
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).stream(messages.capture(), any(), any());
        return messages.getValue().stream().map(Msg::getTextContent).toList();
    }

    private Seed seed(List<Step> steps) {
        String user = IdWorker.getIdStr();
        users.add(user);
        // 与聊天入口同序：先建游标再写消息，种子全部算待整理
        repository.ensureCursor(user);
        Map<String, String> conversations = new LinkedHashMap<>();
        String last = null;
        for (Step step : steps) {
            String conversation = conversations.computeIfAbsent(step.conversation, ignored -> IdWorker.getIdStr());
            String id = insert(user, conversation, step.role, step.content);
            if (step.role.equals("user")) {
                last = id;
            }
        }
        return new Seed(user, conversations, last);
    }

    private String insert(String user, String conversation, String role, String content) {
        String id = IdWorker.getIdStr();
        jdbc.update("insert into t_agent_message (id,conversation_id,user_id,role,content) values (?,?,?,?,?)",
                id, conversation, user, role, content);
        return id;
    }

    private String cursor(String user) {
        return jdbc.queryForObject("select last_message_id from t_agent_memory_cursor where user_id=?", String.class, user);
    }

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("bootstrap/src/main/resources/application.yaml"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("找不到当前应用配置");
        return path;
    }

    private record Seed(String user, Map<String, String> conversations, String lastUserMessage) {
    }

    private record Step(String conversation, String role, String content) {
    }
}
