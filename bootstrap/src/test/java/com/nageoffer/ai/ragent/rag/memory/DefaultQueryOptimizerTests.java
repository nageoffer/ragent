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

package com.nageoffer.ai.ragent.rag.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.memory.DefaultQueryOptimizer;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryContext;
import com.nageoffer.ai.ragent.rag.core.memory.model.OptimizedQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class DefaultQueryOptimizerTests {

    @Test
    void shouldExpandCoreferenceWithRecentUserMessage() {
        DefaultQueryOptimizer optimizer = new DefaultQueryOptimizer();
        MemoryContext context = MemoryContext.builder()
                .workingMemory(List.of(
                        ChatMessage.user("我最近在看 Apache Pulsar 的事务方案"),
                        ChatMessage.assistant("可以重点看 Outbox 方案")
                ))
                .build();

        OptimizedQuery optimizedQuery = optimizer.optimize("这个怎么做？", context);

        Assertions.assertTrue(optimizedQuery.getOptimizedQuery().contains("最近话题"));
        Assertions.assertTrue(optimizedQuery.getOptimizedQuery().contains("Apache Pulsar"));
        Assertions.assertFalse(optimizedQuery.getSearchTerms().isEmpty());
    }

    @Test
    void shouldAppendRecentPreferenceHint() {
        DefaultQueryOptimizer optimizer = new DefaultQueryOptimizer();
        MemoryContext context = MemoryContext.builder()
                .workingMemory(List.of(
                        ChatMessage.user("我喜欢中文回复"),
                        ChatMessage.assistant("已记录")
                ))
                .build();

        OptimizedQuery optimizedQuery = optimizer.optimize("给我解释一下", context);

        Assertions.assertTrue(optimizedQuery.getOptimizedQuery().contains("用户偏好"));
        Assertions.assertTrue(optimizedQuery.getOptimizedQuery().contains("我喜欢中文回复"));
    }
}
