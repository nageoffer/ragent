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

package com.nageoffer.ai.ragent.agent.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

/**
 * ZenMux 网关的请求适配：模型名形如 openai/gpt-6.1-sol:openai，按 model 前缀区分上游家族
 * 只收敛「发了就报错」和「不发就取不到档」两类参数，别的照通用实现
 */
public class ZenMuxFormatter extends OpenAIChatFormatter {

    /**
     * 标准在线档；不发时 ZenMux 可能按账号默认落到别的档，费用与限流口径就跟记录的价格对不上
     */
    static final String SERVICE_TIER = "default";

    private static final String OPENAI_FAMILY = "openai/";

    @Override
    public void applyOptions(OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        sanitize(request);
    }

    /**
     * 上游是 OpenAI 的推理模型：采样参数只接受默认值，传了报 400
     * 摘要器与长期记忆裁决都写死 temperature=0.3，不在这里摘掉就会把这两次调用打挂
     */
    static boolean hasFixedSamplingParams(String model) {
        return model != null && model.startsWith(OPENAI_FAMILY);
    }

    static void sanitize(OpenAIRequest request) {
        String model = request.getModel();
        if (request.getServiceTier() == null) {
            request.setServiceTier(SERVICE_TIER);
        }
        if (hasFixedSamplingParams(model)) {
            request.setTemperature(null);
            request.setTopP(null);
            request.setFrequencyPenalty(null);
            request.setPresencePenalty(null);
            // 推理模型只认 max_completion_tokens，推理 token 也从这个额度里扣
            if (request.getMaxTokens() != null) {
                if (request.getMaxCompletionTokens() == null) {
                    request.setMaxCompletionTokens(request.getMaxTokens());
                }
                request.setMaxTokens(null);
            }
            // thinking_budget 是 DeepSeek / 百炼的方言，ZenMux 不认；思考强度走 reasoning_effort
            request.setThinkingBudget(null);
        }
    }
}
