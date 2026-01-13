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

package com.nageoffer.ai.ragent.rag.rerank;

import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;

import java.util.List;

/**
 * Rerank 服务：对向量检索出来的一批候选文档进行精排，
 * 按“和 query 的相关度”重新排序，并只返回前 topN 条
 */
public interface RerankService {

    /**
     * @param query      用户问题
     * @param candidates 向量检索出来的一批候选文档（通常是 topK 的 3~5 倍）
     * @param topN       最终希望保留的条数（喂给大模型的 K）
     * @return 经过精排后的前 topN 条文档
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
