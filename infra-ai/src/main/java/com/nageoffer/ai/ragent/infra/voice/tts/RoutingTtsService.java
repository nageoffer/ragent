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

package com.nageoffer.ai.ragent.infra.voice.tts;

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TTS 模型路由服务
 */
@Slf4j
@Service
@Primary
public class RoutingTtsService implements TtsService {

    private static final String STREAM_FAILED_MESSAGE = "TTS 流式任务失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "TTS 首音频超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "TTS 未返回有效音频";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final Map<String, TtsClient> clientsByProvider;

    public RoutingTtsService(ModelSelector selector,
                             ModelHealthStore healthStore,
                             List<TtsClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(TtsClient::provider, Function.identity()));
    }

    @Override
    public StreamCancellationHandle synthesize(String text, TtsCallback callback, TtsTaskObserver taskObserver) {
        List<ModelTarget> targets = selector.selectTtsCandidates();
        if (targets.isEmpty()) {
            throw notifyAllFailed(callback, null);
        }

        Throwable lastError = null;
        for (ModelTarget target : targets) {
            TtsClient client = resolveClient(target);
            if (client == null) {
                lastError = new ModelClientException(
                        "TTS 提供商客户端缺失，provider=" + target.candidate().getProvider()
                                + "，modelId=" + target.id(),
                        ModelClientErrorType.CLIENT_ERROR,
                        null
                );
                continue;
            }
            ModelHealthStore.CallPermit permit = healthStore.allowCall(target.id());
            if (permit == null) {
                continue;
            }

            try {
                BinaryProbeStreamBridge bridge = new BinaryProbeStreamBridge(callback);
                StreamCancellationHandle handle;
                try {
                    handle = client.synthesize(text, bridge, target);
                } catch (RuntimeException exception) {
                    bridge.discard();
                    lastError = exception;
                    if (exception instanceof ModelClientException clientException
                            && clientException.getErrorType() == ModelClientErrorType.RATE_LIMITED) {
                        log.warn("TTS 暂无可用调用容量，modelId={}", target.id());
                        continue;
                    }
                    healthStore.markFailure(target.id());
                    log.warn("TTS 任务启动失败，modelId={}，provider={}",
                            target.id(), target.candidate().getProvider(), exception);
                    continue;
                }

                taskObserver.onTaskStarted(handle);
                if (taskObserver.isCancelled()) {
                    bridge.discard();
                    return handle;
                }

                BinaryProbeStreamBridge.ProbeResult result = awaitFirstAudio(bridge, handle, target);
                if (taskObserver.isCancelled()) {
                    bridge.discard();
                    return handle;
                }
                if (result.isSuccess()) {
                    healthStore.markSuccess(target.id());
                    bridge.commit();
                    return handle;
                }

                bridge.discard();
                healthStore.markFailure(target.id());
                cancelQuietly(handle, target);
                lastError = buildLastErrorAndLog(result, target);
            } finally {
                // 归还半开探测名额
                healthStore.releaseHalfOpenPermit(permit);
            }
        }

        throw notifyAllFailed(callback, lastError);
    }

    private TtsClient resolveClient(ModelTarget target) {
        TtsClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("TTS 提供商客户端缺失: provider={}，modelId={}",
                    target.candidate().getProvider(), target.id());
        }
        return client;
    }

    private BinaryProbeStreamBridge.ProbeResult awaitFirstAudio(BinaryProbeStreamBridge bridge,
                                                                StreamCancellationHandle handle,
                                                                ModelTarget target) {
        try {
            long timeoutMs = target.timeoutMs();
            return bridge.awaitFirstAudio(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            RemoteException interrupted = new RemoteException(
                    "TTS 首音频等待被中断", exception, BaseErrorCode.REMOTE_ERROR);
            bridge.onError(interrupted);
            bridge.commit();
            cancelQuietly(handle, target);
            throw interrupted;
        }
    }

    private Throwable buildLastErrorAndLog(BinaryProbeStreamBridge.ProbeResult result, ModelTarget target) {
        String provider = target.candidate().getProvider();
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new ModelClientException(STREAM_FAILED_MESSAGE, ModelClientErrorType.SERVER_ERROR, null);
                log.warn("TTS 失败模型: modelId={}，provider={}，原因: 流式任务失败，切换下一个模型",
                        target.id(), provider, error);
                return error;
            }
            case TIMEOUT -> {
                ModelClientException timeout = new ModelClientException(
                        STREAM_TIMEOUT_MESSAGE, ModelClientErrorType.NETWORK_ERROR, null);
                log.warn("TTS 失败模型: modelId={}，provider={}，原因: 首音频超时，切换下一个模型",
                        target.id(), provider);
                return timeout;
            }
            case NO_CONTENT -> {
                ModelClientException noContent = new ModelClientException(
                        STREAM_NO_CONTENT_MESSAGE, ModelClientErrorType.INVALID_RESPONSE, null);
                log.warn("TTS 失败模型: modelId={}，provider={}，原因: 未返回有效音频，切换下一个模型",
                        target.id(), provider);
                return noContent;
            }
            default -> {
                ModelClientException unknown = new ModelClientException(
                        STREAM_FAILED_MESSAGE, ModelClientErrorType.SERVER_ERROR, null);
                log.warn("TTS 失败模型: modelId={}，provider={}，原因: 流式任务失败（未知类型），切换下一个模型",
                        target.id(), provider);
                return unknown;
            }
        }
    }

    private RuntimeException notifyAllFailed(TtsCallback callback, Throwable lastError) {
        RemoteException failure = new RemoteException(
                "所有 TTS 模型均调用失败",
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(failure);
        return failure;
    }

    private void cancelQuietly(StreamCancellationHandle handle, ModelTarget target) {
        try {
            handle.cancel();
        } catch (RuntimeException exception) {
            log.warn("TTS 失败候选取消异常，modelId={}，provider={}",
                    target.id(), target.candidate().getProvider(), exception);
        }
    }

}
