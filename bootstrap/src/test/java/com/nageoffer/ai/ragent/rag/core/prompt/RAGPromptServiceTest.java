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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RAGPromptServiceTest {

    private static RAGPromptService service(boolean citationEnabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);
        return new RAGPromptService(new PromptTemplateLoader(new DefaultResourceLoader()), properties);
    }

    private static PromptContext kbContext() {
        return PromptContext.builder()
                .kbContext("<content ref=\"1\">资料</content>")
                .kbIntents(List.of())
                .intentChunks(Map.of())
                .build();
    }

    @Test
    void includesCitationRulesFromKnowledgePrompt() {
        String result = service(true).buildSystemPrompt(kbContext());

        assertTrue(result.contains("# 行内引用规则"));
        assertTrue(result.contains("[N](#cite-N)"));

        // 引用只落在正文单元末尾，标题一律不带引用，示例本身也不能出现带引用的标题
        assertTrue(result.contains("标题与小标题一律不加引用"));
        assertTrue(result.contains("## 二级标题\n\n第一部分包含要点 A 和要点 B。[1](#cite-1)"));
        assertFalse(result.contains("## 二级标题 ["));

        // 同一单元依据多份资料时连写编号
        assertTrue(result.contains("`[1](#cite-1)[3](#cite-3)`"));
        assertTrue(result.contains("由两份资料共同支撑。[1](#cite-1)[3](#cite-3)"));

        // 出处只允许数字角标：文档名与内部标签的禁令由基础模板统一声明，引用规则只声明自己是它的唯一例外
        assertTrue(result.contains("**也不得报出处。**"));
        assertTrue(result.contains("标签属性、内部编号"));
        assertTrue(result.contains("除角标外仍不得报文档名、不得罗列来源清单"));
    }

    @Test
    void omitsCitationRulesWhenCitationDisabled() {
        String result = service(false).buildSystemPrompt(kbContext());

        assertFalse(result.contains("# 行内引用规则"));
        assertFalse(result.contains("#cite-"), "关闭引用时不得向模型提及角标格式");
        assertFalse(result.contains("ref=\""), "关闭引用时上下文不注入编号，提示词也不应描述该属性");
    }

    @Test
    void includesMediaAndTableRulesFromKnowledgePrompt() {
        // 模板内的媒体与表格章节和引用开关无关：两种开关状态都必须带上
        for (boolean citationEnabled : new boolean[]{true, false}) {
            String result = service(citationEnabled).buildSystemPrompt(kbContext());

            assertTrue(result.contains("# 链接、图片与附件处理"));
            assertTrue(result.contains("# HTML 表格处理"));
            // HTML 表格章节声明压过格式建议，必须排在基础模板的格式章节之后
            assertTrue(result.indexOf("# 格式与 Markdown 规范") < result.indexOf("# HTML 表格处理"));
        }
    }

    @Test
    void appendsOnlyCitationRulesToCustomIntentTemplate() {
        PromptContext context = PromptContext.builder()
                .kbContext("<content>资料</content>")
                .kbIntents(List.of(intentWithTemplate("# 自定义意图模板")))
                .intentChunks(Map.of("intent-1", List.of(RetrievedChunk.builder().text("资料").build())))
                .build();

        String result = service(true).buildSystemPrompt(context);

        assertTrue(result.contains("# 自定义意图模板"));
        assertFalse(result.contains("# 链接、图片与附件处理"));
        assertFalse(result.contains("# HTML 表格处理"));
        assertTrue(result.contains("# 行内引用规则"));
        assertTrue(result.indexOf("# 自定义意图模板") < result.indexOf("# 行内引用规则"));
    }

    @Test
    void includesKnowledgeRulesFromMixedPrompt() {
        PromptContext context = PromptContext.builder()
                .mcpContext("<data>动态数据</data>")
                .kbContext("<content ref=\"1\">资料</content>")
                .mcpIntents(List.of())
                .kbIntents(List.of())
                .intentChunks(Map.of())
                .build();

        String result = service(true).buildSystemPrompt(context);

        assertTrue(result.contains("# 链接、图片与附件处理"));
        assertTrue(result.contains("# HTML 表格处理"));
        assertTrue(result.contains("# 行内引用规则"));
        assertTrue(result.indexOf("# HTML 表格处理") < result.indexOf("# 行内引用规则"));
    }

    @Test
    void doesNotAppendKnowledgeRulesForMcpOnlyContext() {
        PromptContext context = PromptContext.builder()
                .mcpContext("<data>动态数据</data>")
                .mcpIntents(List.of())
                .build();

        String result = service(true).buildSystemPrompt(context);

        assertFalse(result.contains("# 行内引用规则"));
        assertFalse(result.contains("# 链接、图片与附件处理"));
        assertFalse(result.contains("# HTML 表格处理"));
    }

    private static NodeScore intentWithTemplate(String template) {
        IntentNode node = IntentNode.builder()
                .id("intent-1")
                .promptTemplate(template)
                .build();
        return NodeScore.builder().node(node).build();
    }
}
