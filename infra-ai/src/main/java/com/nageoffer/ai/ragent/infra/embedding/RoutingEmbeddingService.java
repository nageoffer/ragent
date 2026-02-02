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

package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式向量嵌入服务实现类
 * <p>
 * 该服务通过模型路由器选择合适的嵌入模型，并在执行失败时自动进行降级处理
 * 支持单文本和批量文本的向量化操作
 */
@Service
@Primary
public class RoutingEmbeddingService implements EmbeddingService {

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final Map<String, EmbeddingClient> clientsByProvider;

    public RoutingEmbeddingService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<EmbeddingClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
    }

    @Override
    public List<Float> embed(String text) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.embed(text, target)
        );
    }

    @Override
    public List<Float> embed(String text, String modelId) {
        ModelTarget target = resolveTarget(modelId);
        EmbeddingClient client = resolveClient(target);
        if (!healthStore.allowCall(target.id())) {
            throw new RemoteException("Embedding 模型暂不可用: " + target.id());
        }
        try {
            List<Float> vector = client.embed(text, target);
            healthStore.markSuccess(target.id());
            return vector;
        } catch (Exception e) {
            healthStore.markFailure(target.id());
            throw new RemoteException("Embedding 模型调用失败: " + target.id(), e, BaseErrorCode.REMOTE_ERROR);
        }
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.embedBatch(texts, target)
        );
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
        ModelTarget target = resolveTarget(modelId);
        EmbeddingClient client = resolveClient(target);
        if (!healthStore.allowCall(target.id())) {
            throw new RemoteException("Embedding 模型暂不可用: " + target.id());
        }
        try {
            List<List<Float>> vectors = client.embedBatch(texts, target);
            healthStore.markSuccess(target.id());
            return vectors;
        } catch (Exception e) {
            healthStore.markFailure(target.id());
            throw new RemoteException("Embedding 模型调用失败: " + target.id(), e, BaseErrorCode.REMOTE_ERROR);
        }
    }

    @Override
    public int dimension() {
        ModelTarget target = selector.selectDefaultEmbedding();
        if (target == null || target.candidate().getDimension() == null) {
            return 0;
        }
        return target.candidate().getDimension();
    }

    private ModelTarget resolveTarget(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new RemoteException("Embedding 模型ID不能为空");
        }
        return selector.selectEmbeddingCandidates().stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Embedding 模型不可用: " + modelId));
    }

    private EmbeddingClient resolveClient(ModelTarget target) {
        EmbeddingClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            throw new RemoteException("Embedding 模型客户端不存在: " + target.candidate().getProvider());
        }
        return client;
    }
}
