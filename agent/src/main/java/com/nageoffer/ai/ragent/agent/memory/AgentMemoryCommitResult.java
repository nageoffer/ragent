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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;

/**
 * 提交结果；applied 是落库的决策条数，mutated 是记忆集整体有没有变（含合并/淘汰）
 * cleared 表示本批执行了清空，与清掉几条无关：原本就空的清空也要如实告诉用户
 */
public record AgentMemoryCommitResult(AgentMemoryExtractionStatus status, int applied, boolean mutated,
                                      boolean cleared, int clearedItems) {

    public AgentMemoryCommitResult(AgentMemoryExtractionStatus status, int applied, boolean mutated) {
        this(status, applied, mutated, false, 0);
    }
}
