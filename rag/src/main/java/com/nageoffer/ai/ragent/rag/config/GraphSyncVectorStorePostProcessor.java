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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.decorator.GraphSyncingVectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 向量写入图谱同步织入器
 * <p>
 * 与 {@link KeywordSyncVectorStorePostProcessor} 同构：当容器中存在 {@link LightRagClient}
 * （即 rag.graph.type=lightrag）时，把真实的 {@link VectorStoreService} bean 包成
 * {@link GraphSyncingVectorStoreService}，使所有向量写调用点自动附带图谱同步
 * <p>
 * 使用 {@link ObjectProvider} 惰性解析而非 @ConditionalOnBean —— BeanPostProcessor 实例化过早，
 * 条件装配的判定顺序不可靠；惰性解析下 type != lightrag 时直接透传原 bean，零成本
 * <p>
 * 与关键词织入器叠加时装饰器链为 GraphSyncing(KeywordSyncing(真实实现))：两者独立开关、互不影响、链序无关
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphSyncVectorStorePostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LightRagClient> lightRagClientProvider;

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
        if (bean instanceof VectorStoreService vectorStore
                && !(bean instanceof GraphSyncingVectorStoreService)) {
            LightRagClient lightRagClient = lightRagClientProvider.getIfAvailable();
            if (lightRagClient != null) {
                log.info("检测到图谱后端实现，向量写入将同步维护知识图谱, vectorStore={}", beanName);
                return new GraphSyncingVectorStoreService(vectorStore, lightRagClient);
            }
        }
        return bean;
    }
}
