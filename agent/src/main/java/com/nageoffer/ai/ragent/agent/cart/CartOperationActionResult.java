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

package com.nageoffer.ai.ragent.agent.cart;

import java.time.Instant;

/** 区分已完成的核实与尚未取得资格，避免客户端将一次跳过误认为恢复完成。 */
public record CartOperationActionResult(CartOperation operation, boolean verificationStarted,
                                      boolean deferred, Instant retryAt, String notice) {
    public static CartOperationActionResult completed(CartOperation operation) {
        return new CartOperationActionResult(operation, false, false, null, null);
    }
}
