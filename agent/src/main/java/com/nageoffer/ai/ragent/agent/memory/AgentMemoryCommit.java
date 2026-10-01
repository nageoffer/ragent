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

import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;

import java.util.List;

/**
 * 一次提交的全部入参：快照期取到的凭证连同 Judge 决策一起交给提交侧复核
 * merges 只在容量顶到上限那次非空，与决策在同一事务里落地
 */
public record AgentMemoryCommit(String userId,
                                String extractionId,
                                int attemptCount,
                                long expectedRevision,
                                String expectedWatermark,
                                AgentMemorySourceType sourceType,
                                List<AgentMemoryDecision> decisions,
                                List<AgentMemoryMerge> merges) {
}
