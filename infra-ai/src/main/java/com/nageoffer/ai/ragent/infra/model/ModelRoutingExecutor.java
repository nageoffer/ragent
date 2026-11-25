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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    private static final AttributeKey<String> MODEL_ID = AttributeKey.stringKey("ragent.model.id");

    private static final AttributeKey<Boolean> MODEL_FALLBACK = AttributeKey.booleanKey("ragent.model.fallback");

    private static final AttributeKey<String> MODEL_FAILED = AttributeKey.stringKey("ragent.model.failed");

    /**
     * 降级成功不算失败，标 WARNING 让它在 LangFuse 里显眼，又不和 ERROR 混在一起
     */
    private static final AttributeKey<String> LANGFUSE_LEVEL = AttributeKey.stringKey("langfuse.observation.level");

    private final ModelHealthStore healthStore;

    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        String label = capability.getDisplayName();
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        for (ModelTarget target : targets) {
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            ModelHealthStore.CallPermit permit = healthStore.allowCall(target.id());
            if (permit == null) {
                continue;
            }

            try {
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                recordServedModel(target, targets);
                return response;
            } catch (Exception e) {
                if (TaskCancellation.isCancellation(e)) {
                    // 任务取消不是模型故障：不降级、不累计失败，半开探测只归还占用的名额
                    healthStore.releaseHalfOpenPermit(permit);
                    throw TaskCancellation.asCancellation(e);
                }
                last = e;
                Span.current().setAttribute(MODEL_FAILED, target.id() + ": " + e.getMessage());
                if (ModelClientException.isContentRejected(e)) {
                    // 审查拒收说明供应商在线，计入熔断会让一类输入拖垮整个候选；下一个候选可能不做同样的审查
                    healthStore.releaseHalfOpenPermit(permit);
                    log.warn("{} 内容被拒绝，请尝试下一步。modelId={}, provider={}, cause={}", label, target.id(), target.candidate().getProvider(), e.getMessage());
                    continue;
                }
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }

    /**
     * 写到调用方当前的阶段 span 上，没装 OTel SDK 或不在追踪链路里时是空操作
     * 只认本次候选列表的首位：已熔断的模型在 ModelSelector 里就被剔除，换到备选也不标降级，只能从 model.id 看出
     */
    private static void recordServedModel(ModelTarget served, List<ModelTarget> targets) {
        Span span = Span.current();
        span.setAttribute(MODEL_ID, served.id());
        if (served != targets.get(0)) {
            span.setAttribute(MODEL_FALLBACK, true);
            span.setAttribute(LANGFUSE_LEVEL, "WARNING");
        }
    }
}
