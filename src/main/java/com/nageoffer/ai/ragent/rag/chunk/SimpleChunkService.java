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

package com.nageoffer.ai.ragent.rag.chunk;

import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class SimpleChunkService implements ChunkService {

    @Value("${kb.chunk.max-chars:800}")
    private int chunkSize;

    @Value("${kb.chunk.overlap:200}")
    private int overlap;

    @Override
    public List<Chunk> split(String text) {
        List<String> pieces = splitIntoChunks(text, chunkSize, overlap);
        List<Chunk> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            Chunk chunk = Chunk.builder()
                    .content(pieces.get(i))
                    .index(i)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build();
            out.add(chunk);
        }
        return out;
    }

    /**
     * 简单的按长度切 chunk，带 overlap（防止 overlap >= chunkSize 导致卡死）
     */
    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        // 防呆：确保步长 > 0
        int step = Math.max(1, chunkSize - Math.max(0, overlap));

        List<String> chunks = new ArrayList<>();
        int len = text.length();

        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + chunkSize, len);
            String chunk = text.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end == len) {
                break;
            }
        }
        return chunks;
    }
}

