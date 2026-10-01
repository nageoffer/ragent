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

/**
 * 一次抽取的结局；mutated 表示记忆集整体有没有变（含合并/淘汰），与 applied 独立
 * cleared 表示执行过清空，clearedItems 是清掉的条数，两者分开是因为「原本就空」也得单独告诉用户
 */
public record AgentMemoryOutcome(Status status, int applied, int pending, boolean mutated,
                                 boolean cleared, int clearedItems) {

    public AgentMemoryOutcome(Status status, int applied, int pending, boolean mutated) {
        this(status, applied, pending, mutated, false, 0);
    }

    public enum Status {

        /**
         * yaml 长期记忆开关关闭
         */
        DISABLED,

        /**
         * 水位之后没有待处理的用户消息
         */
        NOTHING_PENDING,

        /**
         * 后台门槛没到，攒够再说；flush 不受它挡
         */
        BELOW_THRESHOLD,

        /**
         * 同用户已有在飞抽取
         */
        BUSY,

        /**
         * 仲裁调用或解析失败，这次抽取留待下次机会
         */
        FAILED,

        /**
         * 快照失配，本批作废
         */
        CONFLICT,

        /**
         * 容量拒收，只拒新增不删旧
         */
        CAPACITY_REJECTED,

        /**
         * 判完没落东西，水位照推
         */
        SETTLED_EMPTY,

        /**
         * 判完有落库
         */
        WRITTEN,

        /**
         * 只有显式整理会出现：连跑几批都判完了，却还没轮到本次请求那条消息
         */
        INCOMPLETE
    }

    static AgentMemoryOutcome of(Status status, int pending) {
        return new AgentMemoryOutcome(status, 0, pending, false);
    }

    /**
     * 这批判完并结算了，水位已推过它；显式整理据此决定要不要接着跑下一批
     */
    boolean settled() {
        return status == Status.WRITTEN || status == Status.SETTLED_EMPTY;
    }

    /**
     * 接上后一批：后一批没判完就以它为准，判完了则任一批落过库都算 WRITTEN
     * applied 累计的是生效决策数，不是新增条数；后一批清空过的话，前面几批写的已随之失效，计数只认清空之后
     */
    AgentMemoryOutcome then(AgentMemoryOutcome next) {
        boolean anyMutated = mutated || next.mutated;
        int totalPending = pending + next.pending;
        Status merged = next.settled() && status == Status.WRITTEN ? Status.WRITTEN : next.status;
        if (next.cleared) {
            return new AgentMemoryOutcome(merged, next.applied, totalPending, anyMutated, true, next.clearedItems);
        }
        return new AgentMemoryOutcome(merged, applied + next.applied, totalPending, anyMutated,
                cleared, clearedItems);
    }

    AgentMemoryOutcome withStatus(Status override) {
        return new AgentMemoryOutcome(override, applied, pending, mutated, cleared, clearedItems);
    }

    /**
     * 压根没起跑，一次模型都没叫；后台每轮都会撞上这三种，不值得留 INFO
     */
    public boolean idle() {
        return status == Status.DISABLED
                || status == Status.NOTHING_PENDING
                || status == Status.BELOW_THRESHOLD;
    }
}
