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

package com.nageoffer.ai.ragent.mcp.executor.bit;

import com.nageoffer.ai.ragent.mcp.dao.result.CartLineResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 购物车表头同时给种数和件数：一种商品可以有多件，只报行数会被读成总件数
 */
class BitCartQueryMcpExecutorTest {

    @Test
    void headerCountsKindsAndPieces() {
        assertEquals("【购物车 2 种商品，共 3 件】",
                BitCartQueryMcpExecutor.header(List.of(line(1), line(2))));
    }

    private static CartLineResult line(int quantity) {
        CartLineResult line = new CartLineResult();
        line.setQuantity(quantity);
        return line;
    }
}
