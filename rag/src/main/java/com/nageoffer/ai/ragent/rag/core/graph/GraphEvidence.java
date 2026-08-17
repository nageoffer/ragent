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

package com.nageoffer.ai.ragent.rag.core.graph;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 图谱证据按知识库归属的切分结果
 * <p>
 * LightRAG 单实例即单图、一次查询看的就是全图，归属只能在结果侧按 file_path 判定，
 * 故由 client 判归属、通道分名额：过滤条件为空时全部落在 {@code matched}
 *
 * @param matched   命中目标库的证据，按图谱名次有序
 * @param unmatched 不属于目标库的证据，按图谱名次有序
 */
public record GraphEvidence(List<RetrievedChunk> matched, List<RetrievedChunk> unmatched) {

    public static GraphEvidence empty() {
        return new GraphEvidence(List.of(), List.of());
    }
}
