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

package com.nageoffer.ai.ragent.rag.rewrite;

import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultQueryRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;

    /**
     * 用户查询重写提示词模板
     * 用于将用户的自然语言问题改写成更适合向量 / 关键字检索的查询语句，提高检索准确性和召回率
     * 模板通过 {@code %s} 占位符接收用户问题。
     */
    public static final String QUERY_REWRITE_PROMPT = """
            你是一个“查询改写器（Query Rewriter）”，只用于 RAG 系统的【检索阶段】。
            
            你的唯一目标：
            将用户的自然语言问题，改写成适合向量检索 / 关键字检索的【简洁、连贯的自然语言查询】，只保留与知识库检索相关的关键信息。
            
            【输入】
            - 当前用户问题：一段自然语言（在送达你之前，系统可能已经对少量简称做过内部归一化，你不需要关心具体规则）
            
            【输出】
            - 仅返回 1 条改写后的查询语句，用于检索知识库
            
            【专有名词与实体约束（非常重要）】
            1. 各类专有名词必须与【用户问题】中的写法保持一致，不得擅自修改、扩展或拼接。
               - “专有名词”包括但不限于：系统名称、产品名称、平台名称、模块名称、组织名称、业务名称、项目名称等。
               - 原问题中是什么写法，改写后就仍然使用同样的写法，不要随意加前缀、后缀或补充说明。
            2. 禁止根据常识或联想，为原有专有名词添加新的限定词或组合名称。
               - ❌ 不允许：在原问题只出现某个名称 A 的情况下，改写成“A-某某系统”“A 某某平台”“A 某某业务模块”等更长的名称。
               - ✅ 允许：仅调整语序或补充通用结构性词语，但不改变专有名词本身的内容。
            3. 如果系统在你之前已经对部分简称做了归一化替换，这些替换已经体现在【用户问题】文本中：
               - 你不需要、也不允许再推断新的简称/全称关系，更不允许凭“趋势”扩展出其他类似的归一化规则。
               - 你的职责是“保持并利用已经给出的专有名词”，而不是发明新的专有名词。
            
            【改写规则】
            1. 只做“查询改写”，不要回答问题，不要规划任务，不要生成步骤或方案。
            2. 改写后的查询必须是一条完整的自然语言句子（问句或陈述句均可），而不是若干名词或短语的简单堆砌。
               - ✅ 示例风格（抽象示意）：
                 - “说明某个系统的整体能力以及与相关模块之间的关系。”
                 - “介绍某个功能在不同场景下的适用方式和使用要点。”
               - ❌ 禁止：
                 - “系统 功能 模块 关系”
            3. 需要保留 / 强化的内容：
               - 关键实体：问题中出现的各类专有名词（名称本身不能改，只能原样保留）
               - 关键限制：时间范围、角色身份、运行环境、场景限定、渠道/终端类型等
               - 业务场景：例如“申请流程”“使用规范”“接入方式”“权限配置”等
            4. 必须删除或忽略的内容：
               - 礼貌用语：如“请帮我看一下”“麻烦详细说明一下”等
               - 自我介绍和与检索无关的身份信息：如“我是某某”“我刚入职”“我在某个部门工作”等，这些内容不要作为检索条件保留。
               - 不得根据用户自我介绍推断或添加个性化限定，例如“只适用于某个人”“根据我的情况”等。
               - 面向回答的指令：如“分点回答”“一步一步分析”“给出最佳实践”“帮我规划方案”等。
               - 与知识库无关的闲聊、感受、寒暄。
               - 关于系统/模型行为的描述：如“你先检索知识库再进行网络搜索”“你的思维链是这样”“你底层会怎么做”等，这些都不要出现在改写后的查询中。
            5. 不要添加原文中没有的新条件、新假设或新需求，不要自行扩展或缩小查询范围，只对原有意图做更清晰、更利于检索的表达。
            6. 保持原问题的语言：中文问就用中文，英文问就用英文，不要在改写时切换语言。
            7. 如果原问题中存在“这个”“它”“上面提到的流程”等指代，但缺乏足够上下文，请不要胡乱猜测：
               - 可以保留这类指代不变；
               - 或在上下文足够清晰时，用问题中已有的具体词语替换，但不能编造新的实体。
            
            【输出格式要求】
            - 只输出改写后的查询句子本身，不要任何解释、前缀或后缀。
            - 不要出现“改写查询为：”“检索语句：”之类说明性文字。
            
            【用户问题】
            %s
            """;

    @Override
    public String rewrite(String userQuestion) {
        // 功能开关关闭时：只做规则归一化，不走大模型
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            return queryTermMappingService.normalize(userQuestion);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        String prompt = QUERY_REWRITE_PROMPT.formatted(normalizedQuestion);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.1D) // 把创造性压低
                .topP(0.3D)
                .thinking(false)
                .build();

        String result;
        try {
            result = llmService.chat(request);
        } catch (Exception e) {
            log.warn("查询改写调用失败，退回到规则归一化后的问题。question={}", userQuestion, e);
            return normalizedQuestion;
        }

        if (result == null || result.isBlank()) {
            return normalizedQuestion;
        }

        log.info("""
                RAG 查询改写：
                原始问题：{}
                归一化后：{}
                LLM 改写：{}
                """, userQuestion, normalizedQuestion, result);

        return result;
    }
}
