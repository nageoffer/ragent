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

package com.nageoffer.ai.ragent.rag.core.source;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐问题 grounding 片段装配器
 * <p>
 * 只负责选择：把检索片段（KB 命中）按文档去重、取最高分片段、限定条数后随 assistant 消息落库
 * <p>
 * 与 {@link SourcesAssembler} 职责分离：后者产出面板/预览用的文档级来源（摘录 100 字），
 * 本类产出推荐生成 grounding 用的片段（按文档去重、上限 8 条）
 * <p>
 * 不设字符预算：片段最终只喂给推荐生成器，prompt 体积由消费方在模型调用边界统一控制，
 */
@Component
@RequiredArgsConstructor
public class GroundingChunksAssembler {

    /**
     * grounding 片段条数上限 控制存储与 prompt 体积（文档已去重，8 篇足够支撑 3 条追问的发散）
     */
    private static final int MAX_CHUNKS = 8;

    /**
     * 由检索上下文的意图分片装配 grounding 片段列表
     *
     * @param intentChunks 意图 ID -> 命中片段（KB）
     * @return grounding 片段列表 无命中返回空列表
     */
    public List<GroundingChunk> assemble(Map<String, List<RetrievedChunk>> intentChunks) {
        if (CollUtil.isEmpty(intentChunks)) {
            return List.of();
        }

        // 按 docId 归并 保留最高分片段（与 SourcesAssembler 同语义，保证文档多样性）
        Map<String, RetrievedChunk> bestByDoc = new LinkedHashMap<>();
        intentChunks.values().stream()
                .filter(CollUtil::isNotEmpty)
                .flatMap(List::stream)
                .filter(chunk -> chunk != null
                        && StrUtil.isNotBlank(chunk.getDocId())
                        && StrUtil.isNotBlank(chunk.getText()))
                .forEach(chunk -> bestByDoc.merge(chunk.getDocId(), chunk,
                        (existing, candidate) -> score(candidate) > score(existing) ? candidate : existing));
        if (bestByDoc.isEmpty()) {
            return List.of();
        }

        // 按最高分降序取上限；文本取全文（预算交由消费方在模型调用边界统一控制）
        return bestByDoc.values().stream()
                .sorted(Comparator.comparingDouble(GroundingChunksAssembler::score).reversed())
                .limit(MAX_CHUNKS)
                .map(chunk -> GroundingChunk.builder()
                        .docName(StrUtil.blankToDefault(chunk.getDocName(), chunk.getDocId()))
                        .text(StrUtil.trim(chunk.getText()))
                        .build())
                .toList();
    }

    private static double score(RetrievedChunk chunk) {
        return chunk.getScore() == null ? 0D : chunk.getScore();
    }
}
