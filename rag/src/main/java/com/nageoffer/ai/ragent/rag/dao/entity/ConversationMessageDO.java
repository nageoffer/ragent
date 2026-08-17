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

package com.nageoffer.ai.ragent.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.knowledge.dao.handler.GroundingChunkListTypeHandler;
import com.nageoffer.ai.ragent.knowledge.dao.handler.SourceRefListTypeHandler;
import com.nageoffer.ai.ragent.knowledge.dao.handler.StringListTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 会话消息实体类
 * 用于存储对话过程中的消息记录
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_message", autoResultMap = true)
public class ConversationMessageDO {

    /**
     * 主键 ID，采用雪花算法生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 会话 ID，关联到具体的对话会话
     */
    private String conversationId;

    /**
     * 用户 ID，标识消息发送者
     */
    private String userId;

    /**
     * 角色：user/assistant
     * user: 用户消息
     * assistant: 助手回复
     */
    private String role;

    /**
     * 消息内容，存储实际的消息文本
     */
    private String content;

    /**
     * 深度思考内容
     */
    private String thinkingContent;

    /**
     * 深度思考耗时（秒）
     */
    private Integer thinkingDuration;

    /**
     * 回答来源，文档级来源列表（jsonb 存储，仅 assistant 消息可能有）
     */
    @TableField(typeHandler = SourceRefListTypeHandler.class)
    private List<SourceRef> sources;

    /**
     * 推荐问题 grounding 片段（jsonb 存储，仅 assistant 消息可能有；随消息落库供推荐追问生成 grounding，不参与模型上下文）
     */
    @TableField(typeHandler = GroundingChunkListTypeHandler.class)
    private List<GroundingChunk> retrievedChunks;

    /**
     * 推荐追问问题，答案后懒加载生成（jsonb 存储，仅 assistant 消息可能有；不参与模型上下文）
     */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> recommendedQuestions;

    /**
     * 当前助手消息对应的用户消息 ID
     */
    private String replyToMessageId;

    /**
     * 消息结束状态：NORMAL=正常完成，INTERRUPTED=用户中断，REJECTED=限流拒绝
     */
    private String messageStatus;

    /**
     * 创建时间，自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间，插入和更新时自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 删除标识，逻辑删除字段
     */
    @TableLogic
    private Integer deleted;
}
