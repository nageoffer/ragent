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

package com.nageoffer.ai.ragent.core.chunk;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 分块嵌入服务
 * 职责单一：为已切分的文本块调用嵌入 API 生成向量
 */
@Service
@RequiredArgsConstructor
public class ChunkEmbeddingService {

    private final EmbeddingService embeddingService;

    /**
     * 为分块列表计算嵌入向量
     *
     * @param chunks         已切分的文本块（embedding 字段将被原地填充）
     * @param embeddingModel 嵌入模型 ID，null 时使用系统默认模型
     */
    public void embed(List<VectorChunk> chunks, String embeddingModel) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<VectorChunk> toEmbed = chunks.stream()
            .filter(c -> c.getEmbedding() == null || c.getEmbedding().length == 0)
            .toList();
        if (!toEmbed.isEmpty()) {
            List<String> texts = toEmbed.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .toList();
            List<List<Float>> vectors = StringUtils.hasText(embeddingModel)
                ? embeddingService.embedBatch(texts, embeddingModel)
                : embeddingService.embedBatch(texts);
            applyEmbeddings(toEmbed, vectors);   
        }
    }

    private void applyEmbeddings(List<VectorChunk> chunks, List<List<Float>> vectors) {
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new ClientException("Embedding result size mismatch");
        }
        for (int i = 0; i < chunks.size(); i++) {
            List<Float> row = vectors.get(i);
            if (row == null) {
                throw new ClientException("Embedding result missing, index: " + i);
            }
            float[] vec = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                vec[j] = row.get(j);
            }
            chunks.get(i).setEmbedding(vec);
        }
    }
}
