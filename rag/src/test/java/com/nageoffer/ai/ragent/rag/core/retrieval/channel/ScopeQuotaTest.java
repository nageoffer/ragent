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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScopeQuotaTest {

    private static final RetrievalScope DIRECTED =
            new RetrievalScope(true, 0.9, List.of(), List.of("kb-finance"), List.of("kb-hr", "kb-tech"));

    @Test
    @DisplayName("定向作用域按比例切出补充路名额，其余归主路")
    void directedScopeReservesSupplementQuota() {
        assertEquals(new ScopeQuota(30, 10), ScopeQuota.split(DIRECTED, 40, 0.25));
        assertEquals(new ScopeQuota(15, 5), ScopeQuota.split(DIRECTED, 20, 0.25));
    }

    @Test
    @DisplayName("全局作用域不补充，额度全归主路")
    void globalScopeTakesFullBudget() {
        RetrievalScope global = RetrievalScope.global(0.3, List.of("kb-finance", "kb-hr"));

        assertEquals(new ScopeQuota(40, 0), ScopeQuota.split(global, 40, 0.25));
    }

    @Test
    @DisplayName("命中库已覆盖全部有效库时不补充")
    void fullCoverageTakesFullBudget() {
        RetrievalScope fullCoverage =
                new RetrievalScope(true, 0.9, List.of(), List.of("kb-finance"), List.of());

        assertEquals(new ScopeQuota(40, 0), ScopeQuota.split(fullCoverage, 40, 0.25));
    }

    @Test
    @DisplayName("比例置零关闭补充路")
    void zeroRatioDisablesSupplement() {
        assertEquals(new ScopeQuota(40, 0), ScopeQuota.split(DIRECTED, 40, 0));
    }

    @Test
    @DisplayName("比例过小时补充路至少保留一个名额")
    void tinyRatioKeepsAtLeastOneSlot() {
        assertEquals(new ScopeQuota(39, 1), ScopeQuota.split(DIRECTED, 40, 0.001));
    }

    @Test
    @DisplayName("比例过大时主路至少保留一个名额")
    void oversizedRatioStillLeavesPrimaryOneSlot() {
        // 回归：不夹上界则 ratio=1 切出 primary=0、ratio=1.5 切出 primary=-10，
        // 配置意图是「全部给补充路」，落到 cap 上却成了「主路不封顶」，正好相反
        assertEquals(new ScopeQuota(1, 19), ScopeQuota.split(DIRECTED, 20, 1.0));
        assertEquals(new ScopeQuota(1, 19), ScopeQuota.split(DIRECTED, 20, 1.5));
    }

    @Test
    @DisplayName("额度只剩一个名额时全部归主路")
    void tinyBudgetKeepsEverythingInPrimary() {
        // 保底 1 个名额本会把主路挤成 0，由夹紧的上界 budget-1 收到 0、退化为不补
        assertEquals(new ScopeQuota(1, 0), ScopeQuota.split(DIRECTED, 1, 0.25));
    }

    @Test
    @DisplayName("按名额截断已排序候选，名额为 0 即取零条")
    void capTakesNothingWhenQuotaIsZero() {
        List<String> chunks = List.of("a", "b", "c");

        assertEquals(List.of("a", "b"), ScopeQuota.cap(chunks, 2));
        assertEquals(chunks, ScopeQuota.cap(chunks, 5));
        assertEquals(List.of(), ScopeQuota.cap(chunks, 0),
                "0 若退化为不封顶，「补充路 0 名额」就会变成「补充路不封顶」，与配置意图相反");
    }
}
