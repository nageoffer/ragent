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

package com.nageoffer.ai.ragent.agent.trace;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两道上限的分工：字段上限裁一个叶子，属性上限管住最终那一整串
 * 另一半是内容闸门：关掉时载业务数据的方法返回 null
 */
class AgentTraceSerializerTest {

    /**
     * 字段上限故意配得比属性上限大：单条消息合规，凑够条数照样能把整段撑爆
     */
    private static final int FIELD_LIMIT = 4096;
    private static final int ATTRIBUTE_LIMIT = 8192;

    private final AgentTraceSerializer serializer =
            new AgentTraceSerializer(FIELD_LIMIT, ATTRIBUTE_LIMIT, true);

    /**
     * 显式关闭内容采集的安全降级档；生产默认值是开启
     */
    private final AgentTraceSerializer muted =
            new AgentTraceSerializer(FIELD_LIMIT, ATTRIBUTE_LIMIT, false);

    /**
     * 超限不能直接砍字符串，砍出来的是半截 JSON，LangFuse 解析不了只会当整块纯文本贴出来
     */
    @Test
    void shouldDegradeOversizedAttributeIntoLegalJson() {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            messages.add(new UserMessage("第 " + i + " 条 " + "语".repeat(1000)));
        }

        String json = serializer.messages(messages);

        assertThat(json.length()).isLessThanOrEqualTo(ATTRIBUTE_LIMIT);
        assertThat(JSONUtil.isTypeJSON(json)).isTrue();
        JSONObject overflow = JSONUtil.parseObj(json);
        assertThat(overflow.getBool("_truncated")).isTrue();
        assertThat(overflow.getInt("original_chars")).isGreaterThan(ATTRIBUTE_LIMIT);
        // 退化结果不能只剩个壳，前缀留着才看得出被截掉的是什么
        assertThat(overflow.getStr("preview")).isNotEmpty().startsWith("[");
    }

    /**
     * 单个叶子超限只裁那个叶子，外层结构不受影响
     */
    @Test
    void shouldTruncateSingleFieldWithoutBreakingStructure() {
        Msg msg = AssistantMessage.builder()
                .content(TextBlock.builder().text("答".repeat(FIELD_LIMIT + 500)).build())
                .build();

        String json = serializer.messages(List.of(msg));

        JSONObject output = JSONUtil.parseArray(json).getJSONObject(0);
        String text = output.getJSONArray("blocks").getJSONObject(0).getStr("text");
        assertThat(text).startsWith("答").contains("truncated 500 chars");
    }

    /**
     * 正常完成的根节点只表达业务结果；模型思考和最后一条消息已经在 generation 上，不重复存
     */
    @Test
    void shouldKeepBusinessAnswerWithoutRepeatingModelPayload() {
        Msg msg = AssistantMessage.builder()
                .content(TextBlock.builder().text("多云").build())
                .generateReason(GenerateReason.MODEL_STOP)
                .build();

        JSONObject output = JSONUtil.parseObj(serializer.rootOutput("前置说明多云", msg));

        assertThat(output.keySet()).containsExactly("answer", "generate_reason");
        assertThat(output.getStr("answer")).isEqualTo("前置说明多云");
        assertThat(output.getStr("generate_reason")).isEqualTo(GenerateReason.MODEL_STOP.name());
        // 流还没结束时只保留已经产生的业务正文，不编造 final 与结局
        JSONObject pending = JSONUtil.parseObj(serializer.rootOutput("正文", null));
        assertThat(pending.containsKey("final")).isFalse();
        assertThat(pending.get("generate_reason")).isNull();
    }

    /**
     * 不把流式 delta 当成永远成立的前提：没有累计正文时，最终消息仍是根结果的权威来源
     */
    @Test
    void shouldKeepFinalWhenNormalResultWasNotStreamed() {
        Msg msg = AssistantMessage.builder()
                .content(TextBlock.builder().text("多云").build())
                .generateReason(GenerateReason.MODEL_STOP)
                .build();

        JSONObject output = JSONUtil.parseObj(serializer.rootOutput("", msg));

        assertThat(output.getJSONObject("final").getJSONArray("blocks").getJSONObject(0).getStr("text"))
                .isEqualTo("多云");
    }

    /**
     * 结构化结果不能为了去重一起删掉：权限暂停靠工具块说明在等谁确认、带了什么参数
     */
    @Test
    void shouldKeepStructuredFinalForPermissionPause() {
        Msg msg = AssistantMessage.builder()
                .content(ToolUseBlock.builder()
                        .id("call-1")
                        .name("leave_submit")
                        .input(Map.of("day", "2026-09-14"))
                        .build())
                .generateReason(GenerateReason.PERMISSION_ASKING)
                .build();

        JSONObject output = JSONUtil.parseObj(serializer.rootOutput("", msg));

        assertThat(output.keySet()).containsExactly("answer", "final", "generate_reason");
        assertThat(output.getJSONObject("final").getJSONArray("blocks").getJSONObject(0).getStr("name"))
                .isEqualTo("leave_submit");
    }

    /**
     * 用量各键互不重叠：cachedTokens 含在 inputTokens 里，直接写两份会把缓存部分算两遍
     */
    @Test
    void shouldSplitCachedTokensOutOfInputTokens() {
        JSONObject usage = JSONUtil.parseObj(serializer.usageDetails(120, 30, 20, null, 150));

        assertThat(usage.getInt("input")).isEqualTo(100);
        assertThat(usage.getInt("input_cached_tokens")).isEqualTo(20);
        assertThat(usage.getInt("total")).isEqualTo(150);
        // 上游没报写入量就不写这个键，评测按未知披露，不当成零
        assertThat(usage.containsKey("cache_creation_input_tokens")).isFalse();
    }

    /**
     * Opus 的一笔真实账单：输入 11、写缓存 2896，ChatUsage 的输入总量 2907 里两者都在，拆开后不能重叠
     */
    @Test
    void shouldSplitCacheWriteOutOfInputTokens() {
        JSONObject usage = JSONUtil.parseObj(serializer.usageDetails(2907, 3, 0, 2896, 2910));

        assertThat(usage.getInt("input")).isEqualTo(11);
        assertThat(usage.getInt("input_cached_tokens")).isZero();
        assertThat(usage.getInt("cache_creation_input_tokens")).isEqualTo(2896);
    }

    /**
     * 关掉内容采集时载业务数据的方法一律给 null，写进 span 即空操作
     * 不能退成 "[]" 或 "{}"：那是一个能被读成「这次真的没有消息」的值，缺失才是「没采」
     */
    @Test
    void shouldReturnNullForEveryContentBearingFieldWhenCaptureDisabled() {
        List<Msg> messages = List.of(new UserMessage("我的身份证号是 110101"));
        Msg finalMsg = AssistantMessage.builder()
                .content(TextBlock.builder().text("已受理").build())
                .build();

        assertThat(muted.messages(messages)).isNull();
        assertThat(muted.rootOutput("正文", finalMsg)).isNull();
        assertThat(muted.modelOutput("正文", "思考", List.of())).isNull();
        assertThat(muted.toolCalls(List.of(Map.of("name", "leave_submit")))).isNull();
        assertThat(muted.plainText("北京明天天气")).isNull();
        assertThat(muted.arguments(Map.of("employeeName", "张三"))).isNull();
    }

    /**
     * 结构、参数与用量不随内容一起关：它们是「这次跑了什么、花了多少」，没有它们等于没有观测
     */
    @Test
    void shouldKeepStructuralFieldsWhenCaptureDisabled() {
        List<ToolSchema> tools = List.of(ToolSchema.builder().name("leave_submit").description("提交请假单").build());

        assertThat(muted.toolDefinitions(tools)).contains("leave_submit");
        assertThat(muted.modelParameters(null)).isEqualTo("{}");
        assertThat(JSONUtil.parseObj(muted.usageDetails(120, 30, 20, null, 150)).getInt("output")).isEqualTo(30);
    }

    /**
     * 开启内容采集时不再按键名过滤，嵌套对象与数组里的参数都保持原值
     */
    @Test
    void shouldKeepArgumentValuesWithoutKeyBasedFiltering() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("employeeName", "张三");
        input.put("APIKEY", "sk-real-key");
        input.put("nested", Map.of("password", "p@ss", "day", "2026-09-14"));
        input.put("items", List.of(Map.of("password", "inner-secret")));

        JSONObject output = JSONUtil.parseObj(JSONUtil.toJsonStr(serializer.arguments(input)));

        assertThat(output.getStr("employeeName")).isEqualTo("张三");
        assertThat(output.getStr("APIKEY")).isEqualTo("sk-real-key");
        assertThat(output.getJSONObject("nested").getStr("password")).isEqualTo("p@ss");
        assertThat(output.getJSONObject("nested").getStr("day")).isEqualTo("2026-09-14");
        assertThat(output.getJSONArray("items").getJSONObject(0).getStr("password"))
                .isEqualTo("inner-secret");
    }

    /**
     * 消息里的工具调用块与 metadata 走的是同一条 compact 通道，参数同样保持原值
     */
    @Test
    void shouldKeepArgumentsInsideMessageBlocks() {
        Msg msg = AssistantMessage.builder()
                .content(ToolUseBlock.builder().id("call-1").name("login")
                        .input(Map.of("user", "zhangsan", "password", "p@ss")).build())
                .build();

        String json = serializer.messages(List.of(msg));

        assertThat(json).contains("zhangsan", "p@ss");
    }
}
