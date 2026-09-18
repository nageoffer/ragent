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

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 瀹夊叏妫€鏌ョ粨鏋?
 * <p>
 * 鍖呭惈涓夌缁撴灉绫诲瀷锛歅ASS锛堟斁琛岋級銆丅LOCK锛堥樆鏂級銆乄ARN锛堝憡璀︿絾涓嶉樆鏂級
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SafetyResult {

    private final Type type;
    private final String ruleId;
    private final String message;
    private final SafetyRuleType ruleType;
    private final double confidence;

    public enum Type {
        PASS, BLOCK, WARN
    }

    public static SafetyResult pass() {
        return new SafetyResult(Type.PASS, null, null, null, 0);
    }

    public static SafetyResult block(String ruleId, String message, SafetyRuleType ruleType, double confidence) {
        return new SafetyResult(Type.BLOCK, ruleId, message, ruleType, confidence);
    }

    public static SafetyResult warn(String ruleId, String message, SafetyRuleType ruleType, double confidence) {
        return new SafetyResult(Type.WARN, ruleId, message, ruleType, confidence);
    }

    public boolean isBlocked() {
        return type == Type.BLOCK;
    }

    public boolean isWarned() {
        return type == Type.WARN;
    }

    public boolean isPassed() {
        return type == Type.PASS;
    }
}

