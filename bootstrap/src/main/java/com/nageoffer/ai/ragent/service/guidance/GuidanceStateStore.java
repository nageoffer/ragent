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

package com.nageoffer.ai.ragent.service.guidance;

/**
 * 引导式问答状态存储接口
 * 用于持久化或缓存对话过程中需要引导式问答的状态信息
 */
public interface GuidanceStateStore {

    /**
     * 加载引导式问答状态
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 引导式问答状态对象，如果不存在则可能返回 null
     */
    GuidanceState load(String conversationId, String userId);

    /**
     * 保存引导式问答状态
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param state          要保存的引导式问答状态对象
     */
    void save(String conversationId, String userId, GuidanceState state);

    /**
     * 清除引导式问答状态
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     */
    void clear(String conversationId, String userId);
}
