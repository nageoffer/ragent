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

package com.nageoffer.ai.ragent.ingestion.domain.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点执行结果实体类
 * 表示管道中单个节点执行完成后的结果信息，包含执行状态、是否继续执行后续节点等信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeResult {

    /**
     * 节点是否执行成功
     */
    private boolean success;

    /**
     * 是否应继续执行后续节点
     */
    private boolean shouldContinue;

    /**
     * 结果消息说明
     */
    private String message;

    /**
     * 节点执行失败时的异常信息
     */
    private Throwable error;

    /**
     * 创建成功结果
     *
     * @return 表示执行成功且应继续执行的结果对象
     */
    public static NodeResult ok() {
        return NodeResult.builder().success(true).shouldContinue(true).build();
    }

    /**
     * 创建带消息的成功结果
     *
     * @param message 结果消息
     * @return 表示执行成功且应继续执行的结果对象
     */
    public static NodeResult ok(String message) {
        return NodeResult.builder().success(true).shouldContinue(true).message(message).build();
    }

    /**
     * 创建跳过结果
     *
     * @param reason 跳过原因
     * @return 表示节点被跳过但应继续执行的结果对象
     */
    public static NodeResult skip(String reason) {
        return NodeResult.builder().success(true).shouldContinue(true).message("Skipped: " + reason).build();
    }

    /**
     * 创建失败结果
     *
     * @param error 异常信息
     * @return 表示执行失败且不应继续执行的结果对象
     */
    public static NodeResult fail(Throwable error) {
        return NodeResult.builder()
                .success(false)
                .shouldContinue(false)
                .error(error)
                .message(error == null ? null : error.getMessage())
                .build();
    }

    /**
     * 创建终止结果
     *
     * @param reason 终止原因
     * @return 表示执行成功但应终止管道执行的结果对象
     */
    public static NodeResult terminate(String reason) {
        return NodeResult.builder().success(true).shouldContinue(false).message(reason).build();
    }
}
