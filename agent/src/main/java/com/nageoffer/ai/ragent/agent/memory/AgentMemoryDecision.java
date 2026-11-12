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

import java.util.Date;

/**
 * Judge 的一条决策，描述「这些发言处理完之后，记忆相对当前有效条目要改什么」，每条都带依据的那次用户表达
 *
 * @param sourceMessageId 依据的真实用户消息 id，模型选的
 * @param expressedAt     那条消息的入库时间，由程序按 id 查到，不是模型写的
 */
public record AgentMemoryDecision(Action action, String targetId, String content, String sourceMessageId, Date expressedAt) {

    public enum Action {

        /**
         * 新事实
         */
        ADD,

        /**
         * 在原行改写某条已有条目的正文与依据，正文不变即只换依据
         */
        UPDATE,

        /**
         * 删掉某条已有条目，用户要求忘掉某件事或清空全部时都用它
         */
        DELETE
    }
}
