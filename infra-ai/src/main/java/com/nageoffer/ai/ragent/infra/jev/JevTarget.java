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

package com.nageoffer.ai.ragent.infra.jev;

/**
 * 解析好的 Jev 调用目标
 * 由 {@link JevClient#requireTarget()} 在启动期算一次，配置运行期不会变
 *
 * @param url    完整端点地址
 * @param apiKey 鉴权密钥
 * @param model  模型 ID
 */
public record JevTarget(String url, String apiKey, String model) {
}
