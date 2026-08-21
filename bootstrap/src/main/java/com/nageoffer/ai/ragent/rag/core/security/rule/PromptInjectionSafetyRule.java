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
 * Prompt 娉ㄥ叆妯″紡妫€娴?
 * <p>
 * 妫€娴嬪凡鐭ョ殑 prompt injection / jailbreak 妯″紡锛?
 * 鍖呮嫭 DAN銆佽鑹叉壆婕斿姭鎸併€佸垎闅旂娆洪獥绛夊父瑙佺粫杩囨墜娉曘€?
 */
@Slf4j
@Component
@Order(SafetyRuleOrder.PROMPT_INJECTION)
@RequiredArgsConstructor
public class PromptInjectionSafetyRule implements SafetyRule {

    private final SecurityProperties properties;

    private static final String RULE_ID = "prompt-injection";

    // ===== 鍏稿瀷娉ㄥ叆妯″紡 =====

    /** "Ignore all previous instructions" 绫绘ā寮?*/
    private static final Pattern IGNORE_INSTRUCTIONS = Pattern.compile(
            "ignore\\s+(all\\s+)?(previous|above|the|your|prior)\\s+(instructions?|prompts?|rules?|directives?|context)",
            Pattern.CASE_INSENSITIVE
    );

    /** "Do Anything Now" / DAN 妯″紡 */
    private static final Pattern DAN_PATTERN = Pattern.compile(
            "(do\\s+anything\\s+now|DAN\\s*mode|jailbreak|developer\\s*mode|god\\s*mode|娌℃湁浠讳綍闄愬埗|瑙ｉ櫎鎵€鏈夐檺鍒?",
            Pattern.CASE_INSENSITIVE
    );

    /** 鍒嗛殧绗︽楠楋細鐢?--- 鎴?=== 鎴?XML 鏍囩璇曞浘娉ㄥ叆 system prompt */
    private static final Pattern DELIMITER_INJECTION = Pattern.compile(
            "(</?(system|instruction|prompt|rule|directive)>|\\[system\\]|\\[/system\\]|"
                    + "\\[INST\\]|\\[/INST\\]|<\\|im_start\\|>|<\\|im_end\\|>)",
            Pattern.CASE_INSENSITIVE
    );

    /** 杈撳嚭鏍煎紡鍔寔锛氳姹傛寜鐗瑰畾鏍煎紡杈撳嚭浠ユ毚闇插唴閮ㄤ俊鎭?*/
    private static final Pattern OUTPUT_HIJACK = Pattern.compile(
            "(杈撳嚭浣?鐨?(绯荤粺|鍘熷)?(鎻愮ず璇峾prompt|鎸囦护|瑙勫垯|璁惧畾)|"
                    + "print\\s+your\\s+(system\\s+)?(prompt|instructions|rules)|"
                    + "show\\s+me\\s+your\\s+(system\\s+)?(prompt|instructions|rules)|"
                    + "what\\s+are\\s+your\\s+(system\\s+)?(instructions|rules))",
            Pattern.CASE_INSENSITIVE
    );

    /** 宓屽/閫掑綊鎻愮ず鏀诲嚮锛氳姹傛ā鎷熶竴涓柊鐨勫璇?*/
    private static final Pattern NESTED_PROMPT = Pattern.compile(
            "(鍋囪|鎯宠薄|pretend|imagine)\\s*(浣犵幇鍦ㄦ槸|浣犲湪|浣犳槸).*(鏂皘鍙︿竴涓獆涓嶅悓鐨?\\s*(瀵硅瘽|浼氳瘽|鍦烘櫙|瑙掕壊)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getId() {
        return RULE_ID;
    }

    @Override
    public String getName() {
        return "Prompt 娉ㄥ叆妫€娴?;
    }

    @Override
    public SafetyRuleType getType() {
        return SafetyRuleType.BYPASS;
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

        // === "Ignore instructions" 绫伙紙涓ラ噸锛岀洿鎺?BLOCK锛?===
        if (IGNORE_INSTRUCTIONS.matcher(question).find()) {
            log.warn("瀹夊叏瑙勫垯-娉ㄥ叆妫€娴? question contains 'ignore instructions' pattern");
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈鎸囦护瑕嗙洊鏀诲嚮锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.BYPASS, 0.98);
        }

        // === DAN/jailbreak锛堜弗閲嶏紝鐩存帴 BLOCK锛?===
        if (DAN_PATTERN.matcher(question).find()) {
            log.warn("瀹夊叏瑙勫垯-娉ㄥ叆妫€娴? question contains DAN/jailbreak pattern");
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈瓒婄嫳鏀诲嚮妯″紡锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.BYPASS, 0.98);
        }

        // === 鍒嗛殧绗︽敞鍏ワ紙涓ラ噸锛岀洿鎺?BLOCK锛?===
        if (DELIMITER_INJECTION.matcher(question).find()) {
            log.warn("瀹夊叏瑙勫垯-娉ㄥ叆妫€娴? question contains delimiter injection pattern");
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈缁撴瀯娉ㄥ叆鏀诲嚮锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.ATTACK, 0.97);
        }

        // === 杈撳嚭鏍煎紡鍔寔锛堜腑鍗憋紝鐩存帴 BLOCK锛?===
        if (OUTPUT_HIJACK.matcher(question).find()) {
            log.warn("瀹夊叏瑙勫垯-娉ㄥ叆妫€娴? question attempts to extract system prompt");
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭璇曞浘鑾峰彇绯荤粺鍐呴儴淇℃伅锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.LEAKAGE, 0.95);
        }

        // === 宓屽鎻愮ず鏀诲嚮锛堜腑鍗憋紝WARN锛?===
        if (NESTED_PROMPT.matcher(question).find()) {
            log.info("瀹夊叏瑙勫垯-娉ㄥ叆妫€娴? question contains nested prompt pattern");
            return SafetyResult.warn(RULE_ID,
                    "妫€娴嬪埌鎮ㄧ殑鎻愰棶鍖呭惈瑙掕壊鍒囨崲璇锋眰锛屾湰娆″洖绛斿凡杩藉姞瀹夊叏绾︽潫銆?,
                    SafetyRuleType.BYPASS, 0.7);
        }

        return SafetyResult.pass();
    }
}

