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

import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.decorator.KeywordSyncingVectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 向量写入装饰器织入器
 * <p>
 * 当容器中存在 {@link KeywordIndexService}（即 rag.keyword.type != none）时，
 * 把真实的 {@link VectorStoreService} bean 包成 {@link KeywordSyncingVectorStoreService}，
 * 使所有向量写调用点自动附带关键词索引同步
 * <p>
 * 使用 {@link ObjectProvider} 惰性解析而非 @ConditionalOnBean —— BeanPostProcessor 实例化过早，
 * 条件装配的判定顺序不可靠；惰性解析下 type=none 时直接透传原 bean，零成本
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordSyncVectorStorePostProcessor implements BeanPostProcessor {

    private final ObjectProvider<KeywordIndexService> keywordIndexServiceProvider;

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
        if (bean instanceof VectorStoreService vectorStore
                && !(bean instanceof KeywordSyncingVectorStoreService)) {
            KeywordIndexService keywordIndexService = keywordIndexServiceProvider.getIfAvailable();
            if (keywordIndexService != null) {
                log.info("检测到关键词索引实现，向量写入将同步维护关键词索引, vectorStore={}", beanName);
                return new KeywordSyncingVectorStoreService(vectorStore, keywordIndexService);
            }
        }
        return bean;
    }
}
