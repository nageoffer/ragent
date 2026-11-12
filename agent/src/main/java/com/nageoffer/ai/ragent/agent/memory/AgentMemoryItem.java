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

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/**
 * 一条生效中的长期记忆，原样序列化进 Judge 材料；id 要带给 Judge 才有得指认，来源两列让 Judge 对照表达先后
 *
 * @param sourceMessageId 正文依据的真实用户消息 id，早期数据为空
 * @param expressedAt     那条消息的入库时间，即用户表达时间，早期数据为空
 */
public record AgentMemoryItem(String id, String content, String sourceMessageId,
                              @JsonFormat(pattern = AgentMemoryItem.TIME_PATTERN, timezone = AgentMemoryItem.TIME_ZONE) Date expressedAt) {

    /**
     * 送给 Judge 的时间格式，消息时间与记忆表达时间共用，保留毫秒；只用于展示，程序内比较仍用真实时间值
     */
    static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    static final String TIME_ZONE = "Asia/Shanghai";
}
