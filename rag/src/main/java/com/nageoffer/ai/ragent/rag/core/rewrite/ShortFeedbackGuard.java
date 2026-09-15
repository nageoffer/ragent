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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import java.util.List;

/**
 * 短反馈消息判定：多轮对话中的致谢、确认、纠正等纯反馈短句不进入查询改写，
 * 避免被 LLM 改写为上一轮业务问题后错误路由。
 *
 * <p>判定规则（全部满足才命中）：
 * <ul>
 *   <li>长度不超过 {@link #MAX_LENGTH}（含标点）；</li>
 *   <li>不包含疑问句式或请求指令（防止误伤真实问题）；</li>
 *   <li>包含反馈类关键词（致谢 / 确认 / 评价）。</li>
 * </ul>
 */
public final class ShortFeedbackGuard {

    /** 纯反馈短句的最大长度（字符数，含标点） */
    private static final int MAX_LENGTH = 20;

    /** 反馈类关键词：致谢 / 确认 / 正面评价 */
    private static final List<String> FEEDBACK_KEYWORDS = List.of(
            "谢谢", "感谢", "多谢", "好的", "好滴", "收到", "明白", "明白了", "了解", "了解了", "知道了",
            "不错", "很好", "太好了", "没关系", "不用谢", "没问题", "可以");

    /** 疑问 / 请求标记：出现任一标记说明仍是问题或任务请求，不旁路 */
    private static final List<String> QUESTION_MARKERS = List.of(
            "?", "？", "怎么", "如何", "什么", "为什么", "帮我", "请", "请问");

    private ShortFeedbackGuard() {
    }

    /**
     * 判断给定消息是否为应跳过查询改写的纯反馈短句。
     * @param text 用户消息原文
     * @return 命中反馈判定时返回 {@code true}
     */
    public static boolean isShortFeedback(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_LENGTH) {
            return false;
        }
        for (String marker : QUESTION_MARKERS) {
            if (trimmed.contains(marker)) {
                return false;
            }
        }
        for (String keyword : FEEDBACK_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
