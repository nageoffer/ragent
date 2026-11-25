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

package com.nageoffer.ai.ragent.rag.core.guard;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevNoulQuestion;
import com.nageoffer.ai.ragent.infra.jev.JevRequest;
import com.nageoffer.ai.ragent.infra.jev.JevTarget;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INPUT_RISK_JEV_PROMPT_PATH;

/**
 * Jev 输入审核：待审数据作 state，每个风险类别一条 Noul 命题，一次请求判完
 * <p>
 * 命题原文在 {@link com.nageoffer.ai.ragent.rag.constant.RAGConstant#INPUT_RISK_JEV_PROMPT_PATH}，构造期读一次
 */
public class JevInputRiskDetector implements InputRiskDetector {

    /**
     * Noul 给的是答案为是的概率，过半即命中
     */
    private static final double HIT_THRESHOLD = 0.5D;

    private final JevClient jevClient;

    private final JevTarget target;

    private final List<JevNoulQuestion> questions;

    public JevInputRiskDetector(JevClient jevClient, PromptTemplateLoader promptTemplateLoader) {
        this.jevClient = jevClient;
        this.target = jevClient.requireTarget();
        if (StrUtil.isBlank(target.apiKey())) {
            throw new IllegalStateException(
                    "ragent.input-guard.type=jev 需要配置 Jev 所用供应商的 api-key（默认 ai.providers.zenmux.api-key）");
        }
        this.questions = List.of(
                question(InputRisk.PROMPT_INJECTION, "prompt-injection", promptTemplateLoader),
                question(InputRisk.PROHIBITED_CONTENT, "prohibited-content", promptTemplateLoader));
    }

    @Override
    public Set<InputRisk> detect(String question, Supplier<Optional<PreviousTurn>> previousTurn) {
        String state = InputRiskPayload.render(question, previousTurn.get().orElse(null));
        // JevClient 保证请求过的 key 都有答案，缺一条就整份抛 INVALID_RESPONSE
        Map<String, Double> nouls = jevClient.evaluate(new JevRequest(state, questions), target).nouls();
        Set<InputRisk> risks = EnumSet.noneOf(InputRisk.class);
        for (InputRisk risk : InputRisk.values()) {
            if (nouls.get(risk.name()) >= HIT_THRESHOLD) {
                risks.add(risk);
            }
        }
        return risks;
    }

    /**
     * 缺哪段 section 由 loadSection 在启动期报出来
     */
    private static JevNoulQuestion question(InputRisk risk, String section, PromptTemplateLoader loader) {
        return new JevNoulQuestion(risk.name(),
                loader.loadSection(INPUT_RISK_JEV_PROMPT_PATH, section + "-instructions"),
                loader.loadSection(INPUT_RISK_JEV_PROMPT_PATH, section + "-true"),
                loader.loadSection(INPUT_RISK_JEV_PROMPT_PATH, section + "-false"));
    }
}
