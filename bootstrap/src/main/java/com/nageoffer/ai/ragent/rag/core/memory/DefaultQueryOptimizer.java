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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.memory.model.MemoryContext;
import com.nageoffer.ai.ragent.rag.core.memory.model.OptimizedQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认记忆查询优化器。
 */
@Component
public class DefaultQueryOptimizer implements QueryOptimizer {

    @Override
    public OptimizedQuery optimize(String originalQuery, MemoryContext context) {
        String query = originalQuery == null ? "" : originalQuery.trim().replaceAll("\\s+", " ");
        List<String> terms = new ArrayList<>();
        if (!query.isBlank()) {
            terms.add(query);
        }
        if (containsCoreference(query) && context != null && context.getWorkingMemory() != null) {
            String referent = lastUserMessage(context.getWorkingMemory());
            if (referent != null && !referent.isBlank()) {
                query = query + " 最近话题: " + referent;
                terms.add(referent);
            }
        }
        if (context != null && context.getWorkingMemory() != null) {
            String preferenceHint = recentPreferenceHint(context.getWorkingMemory());
            if (preferenceHint != null && !preferenceHint.isBlank()) {
                query = query + " 用户偏好: " + preferenceHint;
                terms.add(preferenceHint);
            }
        }
        for (String token : query.split("[\\s,，。！？;；]+")) {
            if (token.length() >= 2 && !terms.contains(token)) {
                terms.add(token);
            }
        }
        return OptimizedQuery.builder()
                .originalQuery(originalQuery)
                .optimizedQuery(query)
                .searchTerms(terms)
                .build();
    }

    private boolean containsCoreference(String query) {
        return query.contains("这个")
                || query.contains("那个")
                || query.contains("它")
                || query.contains("这件事")
                || query.contains("that")
                || query.contains("this");
    }

    private String lastUserMessage(List<ChatMessage> workingMemory) {
        for (int i = workingMemory.size() - 1; i >= 0; i--) {
            ChatMessage message = workingMemory.get(i);
            if (message.getRole() == ChatMessage.Role.USER && message.getContent() != null) {
                return message.getContent();
            }
        }
        return null;
    }

    private String recentPreferenceHint(List<ChatMessage> workingMemory) {
        for (int i = workingMemory.size() - 1; i >= 0; i--) {
            ChatMessage message = workingMemory.get(i);
            if (message.getRole() != ChatMessage.Role.USER || message.getContent() == null) {
                continue;
            }
            String content = message.getContent();
            if (content.contains("喜欢") || content.contains("偏好") || content.contains("不喜欢")) {
                return content;
            }
        }
        return null;
    }
}
