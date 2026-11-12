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
 * 一次整理的结局：状态说明有没有提交，changes 是已提交的实际变更，explanation 是 Judge 的判定说明
 * 没提交时两者都为空，不把 Judge 的计划当成已完成的操作
 */
public record AgentMemoryOutcome(Status status, List<String> changes, String explanation) {

    public enum Status {

        /**
         * yaml 长期记忆开关关闭
         */
        DISABLED,

        /**
         * 该用户的记忆正被另一次整理处理，这次没动
         */
        BUSY,

        /**
         * 只有后台会出现：游标之后没有待整理的用户发言
         */
        NOTHING_PENDING,

        /**
         * 只有后台会出现：待整理的用户发言不足门槛，不调模型
         */
        BELOW_THRESHOLD,

        /**
         * 本次处理成功：可能写了记忆，也可能合法地不改；changes 只列内容变更，为空时原因看 explanation
         */
        COMMITTED,

        /**
         * 读取、模型调用或解析失败，决策不合执行要求被整批拒绝，或后台提交时游标已被推过，确认什么都没写
         */
        FAILED,

        /**
         * 本批新内容自身超出容量上限，什么都没写
         */
        CAPACITY_EXCEEDED,

        /**
         * 提交调用抛出异常，库里是否已写入无法确认
         */
        UNKNOWN
    }

    public static AgentMemoryOutcome of(Status status) {
        return new AgentMemoryOutcome(status, List.of(), "");
    }

    static AgentMemoryOutcome committed(AgentMemoryEdit edit, AgentMemoryJudgement judgement) {
        return new AgentMemoryOutcome(Status.COMMITTED, edit.changes(), judgement.explanation());
    }
}
