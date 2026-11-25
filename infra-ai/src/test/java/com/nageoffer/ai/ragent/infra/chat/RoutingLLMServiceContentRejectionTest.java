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

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式路由与同步路由同一口径：审查拒收不计入熔断，接着试下一个候选
 */
class RoutingLLMServiceContentRejectionTest {

    @Test
    void shouldTryNextCandidateWithoutMarkingFailureWhenContentRejected() {
        ModelTarget primary = target("qwen3.8", "bailian");
        ModelTarget fallback = target("deepseek-flash", "deepseek");
        ModelSelector selector = mock(ModelSelector.class);
        when(selector.selectChatCandidates(anyBoolean())).thenReturn(List.of(primary, fallback));
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelHealthStore.CallPermit probe = new ModelHealthStore.CallPermit(primary.id(), 7L);
        when(healthStore.allowCall(primary.id())).thenReturn(probe);
        when(healthStore.allowCall(fallback.id())).thenReturn(new ModelHealthStore.CallPermit(fallback.id(), 0L));

        ChatClient rejecting = mock(ChatClient.class);
        when(rejecting.provider()).thenReturn("bailian");
        when(rejecting.streamChat(any(), any(), any())).thenAnswer(invocation -> {
            StreamCallback callback = invocation.getArgument(1);
            callback.onError(new ModelClientException("bailian 流式请求失败: HTTP 400 code=data_inspection_failed",
                    ModelClientErrorType.CONTENT_REJECTED, 400));
            return mock(StreamCancellationHandle.class);
        });
        ChatClient answering = mock(ChatClient.class);
        when(answering.provider()).thenReturn("deepseek");
        when(answering.streamChat(any(), any(), any())).thenAnswer(invocation -> {
            StreamCallback callback = invocation.getArgument(1);
            callback.onContent("好的");
            return mock(StreamCancellationHandle.class);
        });

        RoutingLLMService service = new RoutingLLMService(selector, healthStore,
                new ModelRoutingExecutor(healthStore), new LlmFirstPacketProbe(), List.of(rejecting, answering));
        service.streamChat(ChatRequest.builder().build(), mock(StreamCallback.class));

        verify(healthStore, never()).markFailure(primary.id());
        verify(healthStore).releaseHalfOpenPermit(probe);
        verify(healthStore).markSuccess(fallback.id());
    }

    private static ModelTarget target(String id, String provider) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider(provider);
        candidate.setModel(id);
        return new ModelTarget(id, candidate, new AIModelProperties.ProviderConfig(), 1_000L);
    }
}
