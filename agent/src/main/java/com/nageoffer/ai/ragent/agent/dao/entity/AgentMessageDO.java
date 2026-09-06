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
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.agent.dao.handler.AgentBlockListTypeHandler;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * Agent 消息表：终答双写落这里，无来源无角标
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_agent_message", autoResultMap = true)
public class AgentMessageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String conversationId;

    private String userId;

    /**
     * user / assistant
     */
    private String role;

    /**
     * 终答正文，与用户所见逐字一致，不截断
     * 唯一读者是改写上下文 loadRecentTurns 与前端的无 blocks 兜底，回放时间线不读它
     */
    private String content;

    /**
     * 思考正文，同 content 只做无 blocks 时的兜底
     */
    private String thinkingContent;

    /**
     * 运行轨迹块（reasoning / answer / tool / confirm / error 有序序列），回放时间线的唯一来源
     * 与上面两个字段有意不等价：剔空块、工具结果截 20k，用作正文会丢字
     */
    @TableField(typeHandler = AgentBlockListTypeHandler.class)
    private List<AgentBlock> blocks;

    private String replyToMessageId;

    /**
     * 参考：{@link AgentMessageStatus}
     */
    private String messageStatus;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
