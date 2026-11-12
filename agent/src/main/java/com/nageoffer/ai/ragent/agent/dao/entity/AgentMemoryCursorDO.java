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

package com.nageoffer.ai.ragent.agent.dao.entity;

import lombok.Data;

import java.util.Date;

/**
 * 每用户一行的长期记忆游标，读写全走注解 Mapper（要 ON CONFLICT 与条件推进）
 */
@Data
public class AgentMemoryCursorDO {

    private String userId;

    /**
     * 已整理到的最后一条用户发言ID，之后的用户发言都待整理；为空表示从该用户第一条发言开始
     */
    private String lastMessageId;

    private Date createTime;

    private Date updateTime;
}
