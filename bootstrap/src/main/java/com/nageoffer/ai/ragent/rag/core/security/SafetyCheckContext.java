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

package com.nageoffer.ai.ragent.rag.core.security;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 瀹夊叏妫€鏌ヤ笂涓嬫枃 鈥斺€?鍖呭惈娴佹按绾垮綋鍓嶉樁娈电殑鎵€鏈夊彲妫€娴嬩俊鎭?
 */
@Data
@Builder
public class SafetyCheckContext {

    /** 鐢ㄦ埛鍘熷闂 */
    private final String originalQuestion;

    /** 鏀瑰啓鍚庣殑闂 */
    private final String rewrittenQuestion;

    /** 缁勮濂界殑娑堟伅鍒楄〃锛堝惈 system prompt + history + evidence + user question锛?*/
    private final List<ChatMessage> messages;

    /** 妫€绱笂涓嬫枃锛堢敤浜庢娴嬩笂涓嬫枃涓槸鍚﹀寘鍚晱鎰熷唴瀹癸級 */
    private final RetrievalContext retrievalCtx;

    /** 鐢ㄦ埛 ID */
    private final String userId;

    /** 浼氳瘽 ID */
    private final String conversationId;

    /** 浠诲姟 ID */
    private final String taskId;
}

