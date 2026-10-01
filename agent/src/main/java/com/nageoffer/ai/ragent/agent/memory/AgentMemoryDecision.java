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

import java.util.List;

/**
 * Judge 的一条决策，描述的是「处理完本批之后该长什么样」而不是逐句事件
 */
public record AgentMemoryDecision(Action action, String targetId, String content) {

    /**
     * 只列真会改库的动作；协议里的 NOOP 在 AgentMemoryJudge.toDecision 就解析成 null，到不了这里
     */
    public enum Action {

        /**
         * 新事实
         */
        ADD,

        /**
         * 新事实取代旧条目，两步在同一事务里
         */
        SUPERSEDE,

        /**
         * 用户明确要求忘掉某条目
         */
        RETRACT,

        /**
         * 用户明确要求清空全部长期记忆；同批只许再带清空之后要记的 ADD，由 AgentMemoryJudge 解析时把关
         */
        CLEAR
    }

    public static AgentMemoryDecision add(String content) {
        return new AgentMemoryDecision(Action.ADD, null, content);
    }

    public static AgentMemoryDecision supersede(String targetId, String content) {
        return new AgentMemoryDecision(Action.SUPERSEDE, targetId, content);
    }

    public static AgentMemoryDecision retract(String targetId) {
        return new AgentMemoryDecision(Action.RETRACT, targetId, null);
    }

    public static AgentMemoryDecision clear() {
        return new AgentMemoryDecision(Action.CLEAR, null, null);
    }

    static boolean containsClear(List<AgentMemoryDecision> decisions) {
        return decisions.stream().anyMatch(decision -> decision.action() == Action.CLEAR);
    }

    /**
     * 会往记忆集里塞正文的两种动作，容量预演只看它们
     */
    public boolean introducesContent() {
        return action == Action.ADD || action == Action.SUPERSEDE;
    }
}
