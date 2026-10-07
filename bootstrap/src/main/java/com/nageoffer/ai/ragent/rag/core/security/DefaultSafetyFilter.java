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

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 瀹夊叏杩囨护鍣ㄩ粯璁ゅ疄鐜?
 * <p>
 * 閫氳繃鏋勯€犲嚱鏁版敞鍏?{@code List<SafetyRule>} 鈥斺€?Spring 鑷姩鏀堕泦鎵€鏈?
 * 瀹炵幇浜?{@link SafetyRule} 鎺ュ彛鐨?Bean锛屾棤闇€鎵嬪姩娉ㄥ唽銆?
 * 鍙傝€冿細{@code DefaultMcpToolRegistry} 鐨勮嚜鍔ㄥ彂鐜版ā寮忋€?
 * <p>
 * 瑙勫垯鎸?{@code @Order} 娉ㄨВ鐨勫€间粠灏忓埌澶т緷娆℃墽琛岋紝閬囧埌棣栦釜 BLOCK 鍗崇煭璺繑鍥炪€?
 * 鎵€鏈?WARN 缁撴灉浼氳鏀堕泦锛屾渶缁堝悎骞跺埌娑堟伅鍒楄〃涓€?
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSafetyFilter implements SafetyFilter {

    private final List<SafetyRule> rules;
    private final SecurityProperties properties;
    private final SafetyAuditLogger auditLogger;

    @Override
    public SafetyResult check(SafetyCheckContext context) {
        // 鎬诲紑鍏冲叧闂?鈫?鍏ㄩ儴鏀捐
        if (!properties.isEnabled()) {
            return SafetyResult.pass();
        }

        if (CollUtil.isEmpty(rules)) {
            log.debug("瀹夊叏瑙勫垯鍒楄〃涓虹┖锛岃烦杩囧畨鍏ㄦ鏌?);
            return SafetyResult.pass();
        }

        List<SafetyResult> warnings = new ArrayList<>();

        for (SafetyRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            SafetyResult result;
            try {
                result = rule.evaluate(context.getOriginalQuestion(), context);
            } catch (Exception e) {
                log.error("瀹夊叏瑙勫垯 {} 鎵ц寮傚父锛岄檷绾т负 PASS", rule.getId(), e);
                continue;
            }

            if (result.isBlocked()) {
                log.warn("瀹夊叏瑙勫垯闃绘柇: ruleId={}, ruleName={}, type={}, confidence={}",
                        rule.getId(), rule.getName(), result.getRuleType(), result.getConfidence());
                auditLogger.log(SafetyAuditEvent.builder()
                        .ruleId(rule.getId())
                        .ruleName(rule.getName())
                        .ruleType(result.getRuleType())
                        .result(SafetyResult.Type.BLOCK)
                        .question(truncate(context.getOriginalQuestion()))
                        .userId(context.getUserId())
                        .conversationId(context.getConversationId())
                        .taskId(context.getTaskId())
                        .message(result.getMessage())
                        .confidence(result.getConfidence())
                        .build());
                return result;
            }

            if (result.isWarned()) {
                log.info("瀹夊叏瑙勫垯鍛婅: ruleId={}, ruleName={}, type={}, confidence={}",
                        rule.getId(), rule.getName(), result.getRuleType(), result.getConfidence());
                warnings.add(result);
                auditLogger.log(SafetyAuditEvent.builder()
                        .ruleId(rule.getId())
                        .ruleName(rule.getName())
                        .ruleType(result.getRuleType())
                        .result(SafetyResult.Type.WARN)
                        .question(truncate(context.getOriginalQuestion()))
                        .userId(context.getUserId())
                        .conversationId(context.getConversationId())
                        .taskId(context.getTaskId())
                        .message(result.getMessage())
                        .confidence(result.getConfidence())
                        .build());
            }
        }

        // 娌℃湁 BLOCK锛屼絾瀛樺湪 WARN 鈫?鍚堝苟杩斿洖棣栦釜 WARN锛堟彁绀烘枃妗堬級
        if (!warnings.isEmpty()) {
            return SafetyResult.warn(
                    warnings.get(0).getRuleId(),
                    buildMergedWarnMessage(warnings),
                    warnings.get(0).getRuleType(),
                    warnings.stream().mapToDouble(SafetyResult::getConfidence).max().orElse(0.5)
            );
        }

        return SafetyResult.pass();
    }

    private String buildMergedWarnMessage(List<SafetyResult> warnings) {
        if (warnings.size() == 1) {
            return warnings.get(0).getMessage();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[瀹夊叏鎻愮ず] 鎮ㄧ殑鎻愰棶瑙﹀彂浜嗗椤瑰畨鍏ㄧ瓥鐣?(");
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) sb.append("銆?);
            sb.append(warnings.get(i).getRuleType().name());
        }
        sb.append(")锛屾湰娆″洖绛斿凡鑷姩杩藉姞瀹夊叏绾︽潫銆?);
        return sb.toString();
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}

