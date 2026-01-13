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

package com.nageoffer.ai.ragent.rag.chat;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.enums.ModelCapability;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.rag.model.ModelHealthStore;
import com.nageoffer.ai.ragent.rag.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.rag.model.ModelSelector;
import com.nageoffer.ai.ragent.rag.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    public String chat(ChatRequest request) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(request.getThinking()),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(request.getThinking());
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException("无可用大模型提供者");
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }

            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
            StreamCallback wrapper = new StreamCallback() {
                @Override
                public void onContent(String content) {
                    awaiter.markContent();
                    callback.onContent(content);
                }

                @Override
                public void onThinking(String content) {
                    awaiter.markContent();
                    callback.onThinking(content);
                }

                @Override
                public void onComplete() {
                    awaiter.markComplete();
                    callback.onComplete();
                }

                @Override
                public void onError(Throwable t) {
                    awaiter.markError(t);
                    callback.onError(t);
                }
            };

            StreamCancellationHandle handle = client.streamChat(request, wrapper, target);
            FirstPacketAwaiter.Result result;
            try {
                result = awaiter.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handle.cancel();
                throw new RemoteException("流式请求被中断", e, BaseErrorCode.REMOTE_ERROR);
            }

            // 判断结果
            if (result.isSuccess()) {
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 失败处理
            healthStore.markFailure(target.id());
            handle.cancel();

            switch (result.getType()) {
                case ERROR:
                    lastError = result.getError();
                    log.warn("{} 流式请求失败，切换下一个模型。modelId：{}，provider：{}",
                            label, target.id(), target.candidate().getProvider(), lastError);
                    break;
                case TIMEOUT:
                    lastError = new RemoteException("流式首包超时", BaseErrorCode.REMOTE_ERROR);
                    log.warn("{} 流式请求超时，切换下一个模型。modelId：{}", label, target.id());
                    break;
                case NO_CONTENT:
                    lastError = new RemoteException("流式请求未返回内容", BaseErrorCode.REMOTE_ERROR);
                    log.warn("{} 流式请求无内容完成，切换下一个模型。modelId：{}", label, target.id());
                    break;
            }
        }

        throw new RemoteException(
                "大模型调用失败，请稍后再试...",
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
    }

    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }
}
