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
import java.util.UUID;

/** 独立于聊天消息的业务状态。停止事实与业务结果分开保存。 */
public record CartOperation(UUID operationId, String userId, String conversationId, String roundId,
                            String skuCode, int quantity, String authorizationRef, String taskId,
                            String status, int writeAttempts, Instant stoppedAt, Instant disconnectedAt,
                            String message, Integer quantityAfter, Instant executedAt, String productName) {
    public CartOperation withProductName(String name) {
        return new CartOperation(operationId, userId, conversationId, roundId, skuCode, quantity,
                authorizationRef, taskId, status, writeAttempts, stoppedAt, disconnectedAt,
                message, quantityAfter, executedAt, name);
    }

    public boolean terminal() {
        return "SUCCEEDED".equals(status) || "REJECTED".equals(status) || "CANCELLED".equals(status);
    }
    public boolean canContinue() {
        return !terminal() && stoppedAt == null && writeAttempts < 3;
    }
}
