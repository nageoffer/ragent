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

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 长期记忆条目：修改在原行上改正文与依据，删除与容量淘汰只写 invalidAt，行不物理删除；不保留逐版本历史
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_agent_memory")
public class AgentMemoryDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    /**
     * 自足陈述句，脱离上下文也读得懂
     */
    private String content;

    /**
     * 见 AgentMemorySourceType
     */
    private String sourceType;

    /**
     * 为空即 ACTIVE，注入与仲裁都只看这一列
     */
    private Date invalidAt;

    /**
     * 当前正文依据的那条用户消息，即用户最近一次明确表达或确认它的发言；早期数据为空
     */
    private String sourceMessageId;

    /**
     * 那条用户消息的入库时间，即用户表达时间，不是本行写入时间；早期数据为空
     */
    private Date expressedAt;

    /**
     * 本行写入时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 本行最近一次被修改（改写、失效）的时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
