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

package com.nageoffer.ai.ragent.rag.core.security.rule;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.security.SafetyCheckContext;
import com.nageoffer.ai.ragent.rag.core.security.SafetyResult;
import com.nageoffer.ai.ragent.rag.core.security.SafetyRule;
import com.nageoffer.ai.ragent.rag.core.security.SafetyRuleOrder;
import com.nageoffer.ai.ragent.rag.core.security.SafetyRuleType;
import com.nageoffer.ai.ragent.rag.core.security.SecurityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 杈撳叆闀垮害闄愬埗瑙勫垯 鈥斺€?鎴柇瓒呴暱鐢ㄦ埛杈撳叆
 * <p>
 * 瓒呴暱杈撳叆鍙兘鏄?DoS 鏀诲嚮鎴?prompt stuffing 灏濊瘯銆?
 */
@Slf4j
@Component
@Order(SafetyRuleOrder.INPUT_LENGTH)
@RequiredArgsConstructor
public class InputLengthSafetyRule implements SafetyRule {

    private final SecurityProperties properties;

    private static final String RULE_ID = "input-length";

    /** 缁濆涓婇檺锛堣秴杩囩洿鎺ユ嫆缁濓紝涓嶇粰 reduction锛?*/
    private static final int ABSOLUTE_MAX = 20000;

    @Override
    public String getId() {
        return RULE_ID;
    }

    @Override
    public String getName() {
        return "杈撳叆闀垮害闄愬埗";
    }

    @Override
    public SafetyRuleType getType() {
        return SafetyRuleType.ATTACK;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled() && properties.getInput().isEnabled();
    }

    @Override
    public SafetyResult evaluate(String question, SafetyCheckContext context) {
        if (StrUtil.isBlank(question)) {
            return SafetyResult.pass();
        }

        int maxLength = properties.getInput().getMaxLength();

        // 缁濆涓婇檺锛氱洿鎺ユ嫆缁?
        if (question.length() > ABSOLUTE_MAX) {
            log.warn("瀹夊叏瑙勫垯-闀垮害闄愬埗: question exceeds absolute max ({} > {})", question.length(), ABSOLUTE_MAX);
            return SafetyResult.block(RULE_ID,
                    "杈撳叆鍐呭杩囬暱锛? + question.length() + " 瀛楃锛夛紝宸茶秴鍑虹郴缁熷厑璁哥殑涓婇檺锛? + ABSOLUTE_MAX + " 瀛楃锛夛紝宸茶瀹夊叏绛栫暐鎷︽埅銆?,
                    SafetyRuleType.ATTACK, 0.99);
        }

        // 瓒呰繃閰嶇疆涓婇檺浣嗘湭鍒扮粷瀵逛笂闄愶細WARN锛堝厑璁镐絾璁板綍锛?
        if (question.length() > maxLength) {
            log.info("瀹夊叏瑙勫垯-闀垮害鍛婅: question length {} exceeds configured max {}", question.length(), maxLength);
            return SafetyResult.warn(RULE_ID,
                    "鎮ㄧ殑杈撳叆杈冮暱锛? + question.length() + " 瀛楃锛夛紝鍙兘褰卞搷鍥炵瓟璐ㄩ噺銆傚缓璁簿绠€闂鍚庨噸璇曘€?,
                    SafetyRuleType.ATTACK, 0.4);
        }

        return SafetyResult.pass();
    }
}

