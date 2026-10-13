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

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 瀹夊叏瀹¤浜嬩欢妯″瀷
 */
@Data
@Builder
public class SafetyAuditEvent {

    /** 瑙勫垯 ID */
    private final String ruleId;

    /** 瑙勫垯鍚嶇О */
    private final String ruleName;

    /** 瑙勫垯鍒嗙被 */
    private final SafetyRuleType ruleType;

    /** 浜嬩欢缁撴灉 */
    private final SafetyResult.Type result;

    /** 鐢ㄦ埛闂锛堟埅鏂悗锛?*/
    private final String question;

    /** 鐢ㄦ埛 ID */
    private final String userId;

    /** 浼氳瘽 ID */
    private final String conversationId;

    /** 浠诲姟 ID */
    private final String taskId;

    /** 闄勫姞娑堟伅 */
    private final String message;

    /** 缃俊搴?*/
    private final double confidence;

    /** 浜嬩欢鏃堕棿 */
    @Builder.Default
    private final LocalDateTime timestamp = LocalDateTime.now();
}

