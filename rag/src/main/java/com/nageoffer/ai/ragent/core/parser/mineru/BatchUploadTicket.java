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

package com.nageoffer.ai.ragent.core.parser.mineru;

/**
 * MinerU 申请上传链接接口的返回凭证(单文件)
 * <p>
 * {@link MinerUClient#requestUpload} 返回:batchId 用于后续轮询,
 * uploadUrl 是 MinerU OSS 的预签名 PUT 链接(24h 有效),把文件字节直接 PUT 上去即可
 *
 * @param batchId   MinerU 分配的 batch_id,轮询/下载凭据
 * @param uploadUrl 文件上传目标 URL,PUT 原始字节,无须鉴权头
 */
public record BatchUploadTicket(
        String batchId,
        String uploadUrl
) {
}
