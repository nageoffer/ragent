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

package com.nageoffer.ai.ragent.core.ingest.embed;

import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化：模型与维度都取自 {@link VectorTarget}，没有可空入参可以回落到系统默认模型
 * <p>
 * 住在编排层而不是分块层：落点是编排层的概念，放在分块层会形成模态层反向依赖编排层的回边
 */
@Service
@RequiredArgsConstructor
public class ChunkEmbeddingService {

    private final EmbeddingService embeddingService;

    /**
     * 为块列表计算向量，逐条校验维度：物理空间的列宽写死在建表语句里，不校验则错误漂到向量库类型转换才暴露
     *
     * @param chunks 待向量化的块，向量文本已由装配阶段保证非空
     * @param target 向量落点：提供模型与必须匹配的维度
     * @return 已向量化的块，与入参一一对应且顺序一致
     */
    public List<EmbeddedChunk> embed(List<Chunk> chunks, VectorTarget target) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> texts = chunks.stream().map(Chunk::embeddingText).toList();
        List<List<Float>> vectors = embeddingService.embedBatch(texts, target.embeddingModel());
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new ServiceException(String.format("向量结果条数与分块不符：期望 %d，实际 %s",
                    chunks.size(), vectors == null ? "null" : String.valueOf(vectors.size())));
        }

        List<EmbeddedChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            List<Float> row = vectors.get(i);
            if (row == null || row.isEmpty()) {
                throw new ServiceException("向量结果缺失，序号：" + i);
            }
            if (row.size() != target.dimension()) {
                throw new ServiceException(String.format(
                        "嵌入维度与部署级向量空间不符：模型 %s 输出 %d 维，物理空间要求 %d 维（分区 %s）"
                                + "——请改用同维度的嵌入模型，或调整部署级维度并重建向量空间",
                        target.embeddingModel(), row.size(), target.dimension(), target.partition()));
            }
            float[] vector = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                vector[j] = row.get(j);
            }
            result.add(new EmbeddedChunk(chunks.get(i), vector));
        }
        return result;
    }
}
