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

import java.util.regex.Pattern;

/**
 * 鏁忔劅淇℃伅姝ｅ垯妫€娴?鈥斺€?妫€娴嬬敤鎴烽棶棰樹腑鏄惁鍖呭惈鍑瘉銆佸瘑閽ョ瓑鏁忔劅鏁版嵁
 * <p>
 * 闃叉鐢ㄦ埛閫氳繃闂鏂囨湰娉ㄥ叆 API Key 鎴栦吉瑁呭唴閮ㄥ嚟璇併€?
 */
@Slf4j
@Component
@Order(SafetyRuleOrder.SENSITIVE_PATTERN)
@RequiredArgsConstructor
public class SensitivePatternSafetyRule implements SafetyRule {

    private final SecurityProperties properties;

    private static final String RULE_ID = "sensitive-pattern";

    /** 鎵嬫満鍙凤紙涓浗澶ч檰锛?*/
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");

    /** 韬唤璇佸彿锛?8浣嶏級 */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");

    /** OpenAI/DeepSeek 椋庢牸鐨?API Key */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[a-zA-Z0-9_-]{20,}");

    /** 閫氱敤 API Key 妯″紡 */
    private static final Pattern GENERIC_KEY_PATTERN = Pattern.compile(
            "(api[_\\s]?key|api[_\\s]?secret|access[_\\s]?key|secret[_\\s]?key)\\s*[:=]\\s*['\"]?\\S+['\"]?",
            Pattern.CASE_INSENSITIVE
    );

    /** JWT Token 妯″紡 */
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[a-zA-Z0-9_-]*\\.eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]+");

    @Override
    public String getId() {
        return RULE_ID;
    }

    @Override
    public String getName() {
        return "鏁忔劅淇℃伅-姝ｅ垯妫€娴?;
    }

    @Override
    public SafetyRuleType getType() {
        return SafetyRuleType.LEAKAGE;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public SafetyResult evaluate(String question, SafetyCheckContext context) {
        if (StrUtil.isBlank(question)) {
            return SafetyResult.pass();
        }

        // 妫€娴?API Key 鐩存帴鍑虹幇
        if (API_KEY_PATTERN.matcher(question).find() || JWT_PATTERN.matcher(question).find()) {
            log.warn("瀹夊叏瑙勫垯-鍑瘉娉勯湶: question contains API key or JWT pattern");
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈鐤戜技 API 鍑瘉锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€傝鍕垮湪瀵硅瘽涓彂閫佸瘑閽ヤ俊鎭€?,
                    SafetyRuleType.LEAKAGE, 0.98);
        }

        // 妫€娴嬮€氱敤瀵嗛挜妯″紡
        if (GENERIC_KEY_PATTERN.matcher(question).find()) {
            log.warn("瀹夊叏瑙勫垯-瀵嗛挜妯″紡: question matches generic key pattern");
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈鐤戜技瀵嗛挜閰嶇疆锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.LEAKAGE, 0.9);
        }

        // 妫€娴嬪ぇ閲忔墜鏈哄彿/韬唤璇侊紙鍙兘鐨勬嫋搴撹涓猴級
        int phoneCount = countMatches(PHONE_PATTERN, question);
        int idCardCount = countMatches(ID_CARD_PATTERN, question);

        if (idCardCount >= 3 || phoneCount >= 5) {
            log.warn("瀹夊叏瑙勫垯-鎵归噺闅愮: phone={}, idCard={}", phoneCount, idCardCount);
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈澶ч噺涓汉闅愮鏁版嵁锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.LEAKAGE, 0.95);
        }

        if (idCardCount >= 1 || phoneCount >= 2) {
            log.info("瀹夊叏瑙勫垯-涓汉闅愮鎻愮ず: phone={}, idCard={}", phoneCount, idCardCount);
            return SafetyResult.warn(RULE_ID,
                    "妫€娴嬪埌鎮ㄧ殑杈撳叆鍖呭惈涓汉鏁忔劅淇℃伅锛岃娉ㄦ剰淇濇姢闅愮銆?,
                    SafetyRuleType.LEAKAGE, 0.6);
        }

        return SafetyResult.pass();
    }

    private int countMatches(Pattern pattern, String text) {
        int count = 0;
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}

