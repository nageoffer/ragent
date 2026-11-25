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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 鍏抽敭璇嶅尮閰嶈鍒?鈥斺€?浜旂被瀹夊叏鍏抽敭璇嶉粦鍚嶅崟
 * <p>
 * 瀵圭敤鎴烽棶棰樺仛鍏抽敭璇嶅寘鍚尮閰嶏紝瑕嗙洊 5 绫诲満鏅€?
 * 鍗曚竴浣庨闄╁叧閿瘝鍛戒腑鏃朵粎 WARN锛屽涓綆椋庨櫓鍏抽敭璇嶅悓鏃跺嚭鐜版墠 BLOCK銆?
 */
@Slf4j
@Component
@Order(SafetyRuleOrder.KEYWORD)
@RequiredArgsConstructor
public class KeywordSafetyRule implements SafetyRule {

    private final SecurityProperties properties;

    private static final String RULE_ID = "keyword-safety";

    // ===== 缁曡繃闄愬埗鍏抽敭璇?=====
    private static final Set<String> DEFAULT_BYPASS_KEYWORDS = Set.of(
            "蹇樿瑙勫垯", "蹇界暐鎻愮ず", "蹇界暐鎵€鏈?, "蹇界暐浠ヤ笂", "鏂版寚浠?, "浣犵幇鍦ㄦ槸",
            "鍒囨崲瑙掕壊", "system prompt", "ignore instructions", "ignore all",
            "ignore previous", "forget rules", "涓嶈绠′箣鍓嶇殑", "閲嶆柊璁惧畾",
            "浣犵殑鏂拌韩浠?, "浠庣幇鍦ㄥ紑濮嬩綘鏄?, "鎵紨", "瑙掕壊鎵紨"
    );

    // ===== 浼€犺韩浠藉叧閿瘝 =====
    private static final Set<String> DEFAULT_IMPERSONATION_KEYWORDS = Set.of(
            "鎴戞槸绠＄悊鍛?, "鎴戞槸寮€鍙戣€?, "瓒呯骇绠＄悊鍛?, "绯荤粺绠＄悊鍛?, "鎴戞槸绯荤粺",
            "i am admin", "i am developer", "root 鏉冮檺", "鎴戞槸 root",
            "鎴戞槸鍚庡彴", "鎴戞槸杩愮淮", "鍐呴儴浜哄憳"
    );

    // ===== 鏀诲嚮鍏抽敭璇?=====
    private static final Set<String> DEFAULT_ATTACK_KEYWORDS = Set.of(
            "<script>", "DROP TABLE", "DELETE FROM", "exec(", "eval(",
            "../../../", "..\\..\\..\\", "SELECT * FROM", "UNION SELECT",
            "<img onerror", "onerror=", "javascript:", "data:text/html"
    );

    // ===== 鏁忔劅淇℃伅娉勯湶鍏抽敭璇?=====
    private static final Set<String> DEFAULT_LEAKAGE_KEYWORDS = Set.of(
            "API Key", "api_key", "瀵嗙爜", "password", "瀵嗛挜", "secret",
            "token", "access key", "access_key", "鏁版嵁搴撳瘑鐮?, "閰嶇疆鏂囦欢",
            "application.yaml", "application.yml", "浣犵殑绯荤粺鎻愮ず璇?,
            "浣犵殑 prompt", "your prompt", "show me your instructions"
    );

    // ===== 婊ョ敤鏉冮檺鍏抽敭璇?=====
    private static final Set<String> DEFAULT_ABUSE_KEYWORDS = Set.of(
            "鎵ц鍛戒护", "鍒犻櫎鏂囦欢", "鍙戦€侀偖浠?, "璋冪敤 API", "閲嶅惎鏈嶅姟",
            "shutdown", "rm -rf", "curl ", "wget ", "sudo ",
            "淇敼鏁版嵁搴?, "drop database", "format c:"
    );

    @Override
    public String getId() {
        return RULE_ID;
    }

    @Override
    public String getName() {
        return "鍏抽敭璇?瀹夊叏妫€娴?;
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

        String lower = question.toLowerCase();

        // 鎸変弗閲嶇▼搴﹀垎绾ф鏌?
        // === 楂樺嵄锛氭敾鍑荤被鍏抽敭璇嶏紙鐩存帴 BLOCK锛?===
        int attackHits = countHits(lower, DEFAULT_ATTACK_KEYWORDS);
        if (attackHits > 0) {
            log.warn("瀹夊叏瑙勫垯-鏀诲嚮妫€娴? question contains {} attack keywords", attackHits);
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈涓嶅畨鍏ㄧ殑浠ｇ爜鐗囨锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.ATTACK, 0.95);
        }

        // === 楂樺嵄锛氭互鐢ㄦ潈闄愶紙鐩存帴 BLOCK锛?===
        int abuseHits = countHits(lower, DEFAULT_ABUSE_KEYWORDS);
        if (abuseHits > 0) {
            log.warn("瀹夊叏瑙勫垯-婊ョ敤鏉冮檺: question contains {} abuse keywords", abuseHits);
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈瓒婃潈鎿嶄綔璇锋眰锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.ABUSE, 0.95);
        }

        // === 涓嵄锛氫吉閫犺韩浠斤紙鐩存帴 BLOCK锛?===
        int impersonationHits = countHits(lower, DEFAULT_IMPERSONATION_KEYWORDS);
        if (impersonationHits > 0) {
            log.warn("瀹夊叏瑙勫垯-浼€犺韩浠? question contains impersonation keywords");
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍖呭惈韬唤浼€犲皾璇曪紝宸茶瀹夊叏绛栫暐鎷︽埅銆?,
                    SafetyRuleType.IMPERSONATION, 0.9);
        }

        // === 涓嵄锛氱粫杩囬檺鍒讹紙WARN 鎴栫粍鍚堝嚭鐜版椂 BLOCK锛?===
        int bypassHits = countHits(lower, DEFAULT_BYPASS_KEYWORDS);
        // === 浣庡嵄锛氫俊鎭硠闇茶瀵硷紙WARN 鎴栫粍鍚堝嚭鐜版椂 BLOCK锛?===
        int leakageHits = countHits(lower, DEFAULT_LEAKAGE_KEYWORDS);

        // 澶氱被鍏抽敭璇嶅悓鏃跺嚭鐜帮紝鍗囩骇涓?BLOCK
        int categoriesHit = (bypassHits > 0 ? 1 : 0) + (leakageHits > 0 ? 1 : 0);
        int totalHits = bypassHits + leakageHits;

        if (totalHits >= 3 || categoriesHit >= 2) {
            log.warn("瀹夊叏瑙勫垯-缁勫悎鍖归厤: bypass={}, leakage={}, categories={}", bypassHits, leakageHits, categoriesHit);
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍙兘璇曞浘缁曡繃绯荤粺瀹夊叏绛栫暐锛屽凡琚嫤鎴€?,
                    SafetyRuleType.BYPASS, 0.85);
        }

        if (bypassHits >= 2) {
            log.warn("瀹夊叏瑙勫垯-缁曡繃闄愬埗: question contains {} bypass keywords", bypassHits);
            return SafetyResult.block(RULE_ID,
                    "妫€娴嬪埌杈撳叆鍐呭鍙兘璇曞浘缁曡繃绯荤粺闄愬埗锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€?,
                    SafetyRuleType.BYPASS, 0.8);
        }

        if (bypassHits == 1 || leakageHits >= 1) {
            log.info("瀹夊叏瑙勫垯-鍛婅: bypass={}, leakage={}, 杩藉姞瀹夊叏鎻愮ず", bypassHits, leakageHits);
            return SafetyResult.warn(RULE_ID,
                    "妫€娴嬪埌鎮ㄧ殑鎻愰棶鍙兘娑夊強绯荤粺瀹夊叏杈圭晫锛屾湰娆″洖绛斿凡鑷姩杩藉姞瀹夊叏绾︽潫銆?,
                    SafetyRuleType.BYPASS, 0.5);
        }

        return SafetyResult.pass();
    }

    private int countHits(String text, Set<String> keywords) {
        return (int) keywords.stream().filter(text::contains).count();
    }
}

