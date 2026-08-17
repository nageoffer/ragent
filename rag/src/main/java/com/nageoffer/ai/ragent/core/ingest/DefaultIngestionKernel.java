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

package com.nageoffer.ai.ragent.core.ingest;

import com.nageoffer.ai.ragent.core.chunk.ChunkingService;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.mime.MimeTypeDetector;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄取内核默认实现：固定五步骨架，全文唯一一条摄取执行序列
 * <p>
 * 入口不收 MIME 也不收嵌入模型，任务状态与摄取日志一概不碰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultIngestionKernel implements IngestionKernel {

    /**
     * 解析器 options 键：原始文件名，写进块来源信息
     */
    private static final String OPT_SOURCE_FILE = "sourceFile";

    /**
     * 解析器 options 键：文档 ID，决定图片资产的归属目录 {@code assets/{docId}/...}
     */
    private static final String OPT_DOCUMENT_ID = "documentId";

    private final ParserRegistry parserRegistry;
    private final ChunkingService chunkingService;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final ChunkIndexWriter chunkIndexWriter;

    @Override
    public IngestionOutcome run(DocumentRef doc,
                                byte[] bytes,
                                IngestionSpec spec,
                                VectorTarget target) {
        if (bytes == null || bytes.length == 0) {
            throw new ClientException("文件内容为空：docId=" + doc.docId());
        }
        IngestionSpec effectiveSpec = spec == null ? IngestionSpec.defaults() : spec;

        // ① identity：全链路唯一一次类型识别
        String mimeType = MimeTypeDetector.detect(bytes, doc.filename());
        if (!StringUtils.hasText(mimeType)) {
            throw new ClientException("无法识别文件类型：docId=" + doc.docId() + ", filename=" + doc.filename());
        }

        // ② parse：(MIME × 档位) → 解析器
        long parseStart = System.currentTimeMillis();
        DocumentParser parser = parserRegistry.require(mimeType, effectiveSpec.parseProfile());
        ParsedDocument parsed = parser.parseStructured(bytes, mimeType, parserOptions(doc));
        List<Block> blocks = parsed.blocks() == null ? List.of() : parsed.blocks();
        long parseMillis = System.currentTimeMillis() - parseStart;
        log.info("摄取-解析完成 docId={} mime={} 档位={} 解析器={} blocks={}",
                doc.docId(), mimeType, effectiveSpec.parseProfile().getCode(), parser.getParserType(), blocks.size());

        // ③ chunk：Block 类型 → chunker + 预算
        long chunkStart = System.currentTimeMillis();
        List<Chunk> chunks = chunkingService.chunk(blocks, effectiveSpec.budget());
        long chunkMillis = System.currentTimeMillis() - chunkStart;

        if (chunks.isEmpty()) {
            throw new ClientException("分块结果为空：docId=" + doc.docId() + ", mime=" + mimeType);
        }

        // ④ embed：模型与维度都来自落点，此处校验维度
        long embedStart = System.currentTimeMillis();
        List<EmbeddedChunk> embedded = chunkEmbeddingService.embed(chunks, target);
        long embedMillis = System.currentTimeMillis() - embedStart;

        // ⑤ index：扇出到全部落点，事务边界在写入器内
        long indexStart = System.currentTimeMillis();
        chunkIndexWriter.replaceDocument(target, doc, embedded);
        long indexMillis = System.currentTimeMillis() - indexStart;

        return new IngestionOutcome(mimeType, parser.getParserType(), blocks.size(), chunks,
                new IngestionOutcome.IngestionTimings(parseMillis, chunkMillis, embedMillis, indexMillis));
    }

    /**
     * 组装解析器入参：docId 必须传，解析器用它给图片资产命名，漏传则资产与文档失联
     */
    private Map<String, Object> parserOptions(DocumentRef doc) {
        Map<String, Object> options = new HashMap<>();
        if (StringUtils.hasText(doc.filename())) {
            options.put(OPT_SOURCE_FILE, doc.filename());
        }
        options.put(OPT_DOCUMENT_ID, doc.docId());
        return options;
    }
}
