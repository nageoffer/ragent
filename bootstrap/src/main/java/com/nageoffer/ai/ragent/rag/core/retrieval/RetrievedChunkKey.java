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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.crypto.digest.DigestUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

/**
 * 检索链路内统一的 Chunk 身份键。
 *
 * <p>优先使用持久 Chunk ID；ID 缺失时退化为文本 SHA-256，供去重、融合与意图归属使用同一规则。
 */
public final class RetrievedChunkKey {

    private RetrievedChunkKey() {
    }

    public static String of(RetrievedChunk chunk) {
        return chunk.getId() != null
                ? chunk.getId()
                : DigestUtil.sha256Hex(chunk.getText() == null ? "" : chunk.getText());
    }
}
