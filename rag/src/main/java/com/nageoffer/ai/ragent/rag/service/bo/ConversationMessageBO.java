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

package com.nageoffer.ai.ragent.rag.service.bo;

import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话消息业务对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessageBO {

    /**
     * 对话ID
     */
    private String conversationId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 角色：system/user/assistant
     */
    private String role;

    /**
     * 消息内容
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
     * 回答来源，文档级来源列表（仅 assistant 消息可能有）
     */
    private List<SourceRef> sources;

    /**
     * 推荐问题 grounding 片段（仅 assistant 消息可能有，供推荐追问生成 grounding）
     */
    private List<GroundingChunk> retrievedChunks;

    /**
     * 当前助手消息对应的用户消息 ID
     */
    private String replyToMessageId;

    /**
     * 消息结束状态：NORMAL=正常完成，INTERRUPTED=用户中断，REJECTED=限流拒绝
     */
    private String messageStatus;
}
