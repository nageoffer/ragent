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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;

/**
 * 推荐追问问题服务
 * <p>
 * 推荐问题缓存读取与生成入口
 */
public interface RecommendedQuestionService {

    /**
     * 幂等生成指定 assistant 消息的推荐追问问题
     *
     * @param messageId 消息ID（须为 assistant 消息）
     * @param userId    用户ID（校验归属）
     * @return 推荐追问结果
     */
    RecommendedQuestionsPayload generate(String messageId, String userId);
}
