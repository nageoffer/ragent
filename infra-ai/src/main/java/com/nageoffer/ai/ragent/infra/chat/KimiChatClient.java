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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.stereotype.Service;

/**
 * 月之暗面 Kimi 开放平台 ChatClient
 * K3 系思考常开、关不掉，没有 thinking 参数，只能用顶层 reasoning_effort 调强度
 */
@Service
public class KimiChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        return ModelProvider.KIMI.getId();
    }

    /**
     * K3 系 temperature 固定 1.0、top_p 固定 0.95，传其他值会报错，业务侧设的采样参数在这里摘掉
     * 不要思考时降到 low：默认是 max，standard 档会白等一段长思考
     */
    @Override
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        body.remove("temperature");
        body.remove("top_p");
        if (!Boolean.TRUE.equals(request.getThinking())) {
            body.addProperty("reasoning_effort", "low");
        }
    }

    @Override
    @RagTraceNode(name = "kimi-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request, target);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request, callback, target);
    }
}
