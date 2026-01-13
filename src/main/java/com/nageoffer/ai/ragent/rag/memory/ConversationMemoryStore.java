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

import com.nageoffer.ai.ragent.convention.ChatMessage;

import java.util.List;

/**
 * 对话记忆存储接口
 * 提供对话历史记录的加载、追加和缓存刷新功能
 */
public interface ConversationMemoryStore {

    /**
     * 加载对话历史记录
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 对话历史消息列表
     */
    List<ChatMessage> loadHistory(String conversationId, String userId);

    /**
     * 追加消息到对话历史
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param message        要追加的消息
     */
    void append(String conversationId, String userId, ChatMessage message);

    /**
     * 刷新对话缓存
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     */
    void refreshCache(String conversationId, String userId);
}
