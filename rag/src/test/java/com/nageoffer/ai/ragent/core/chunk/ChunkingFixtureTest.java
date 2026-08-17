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

import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkPacker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.CodeChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingHandler;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HtmlTableChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ImageChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ListChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.parser.CsvDocumentParser;
import com.nageoffer.ai.ragent.core.parser.MarkdownDocumentParser;
import com.nageoffer.ai.ragent.core.parser.TikaDocumentParser;
import com.nageoffer.ai.ragent.core.parser.mime.MimeTypeDetector;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 分块结果核对报告：fixture 字节 → 真解析器 → 真 chunker → 成品块，逐块导出到 {@code target/chunking-report.md}
 * <p>
 * 不做断言，只把切分结果摊开给人看；同一份 markdown 另有 pdf / docx 形态，那两种走 MinerU 外部服务，
 * 离线跑不了，需要从知识库上传后人工核对
 */
class ChunkingFixtureTest {

    private static final String FIXTURE_DIR = "/fixtures/chunking/";

    private static final String MANUAL = "merchant-manual.md";
    private static final String NOTES = "service-notes.txt";
    private static final String RECORDS = "order-records.csv";

    /**
     * 收紧预算：小体量 fixture 在默认 1024 下几乎不切，压到 300 才能让切分与合并同时现形
     */
    private static final ChunkBudget TIGHT = new ChunkBudget(300, 60, 5);

    /**
     * 只让表格行数硬上限起作用的预算：体量给足，分组必然由 rowsPerChunk 决定
     */
    private static final ChunkBudget CAP_ONLY = new ChunkBudget(4000, 200, 5);

    private static final List<BlockChunker<?>> CHUNKERS = List.of(
            new HeadingChunker(),
            new ParagraphChunker(),
            new TableChunker(),
            new HtmlTableChunker(),
            new ImageChunker(),
            new CodeChunker(),
            new ListChunker()
    );

    /**
     * 只装离线可构造的解析器：MinerU 与图片解析器依赖外部服务，本类的 fixture 也用不到它们
     */
    private static final ParserRegistry REGISTRY = new ParserRegistry(List.of(
            new MarkdownDocumentParser(),
            new CsvDocumentParser(),
            new TikaDocumentParser()
    ));

    @Test
    void shouldExportChunkingReportForManualReview() throws IOException {
        StringBuilder report = new StringBuilder("# 分块结果核对报告\n");
        for (String fixture : List.of(MANUAL, NOTES, RECORDS)) {
            List<Block> blocks = parse(fixture);
            report.append("\n## ").append(fixture).append('\n');
            for (ChunkBudget budget : List.of(ChunkBudget.defaults(), TIGHT, CAP_ONLY)) {
                appendBudgetSection(report, chunk(blocks, budget), budget);
            }
        }
        Files.writeString(Path.of("target", "chunking-report.md"), report.toString(), StandardCharsets.UTF_8);
    }

    private static void appendBudgetSection(StringBuilder report, List<Chunk> chunks, ChunkBudget budget) {
        report.append("\n### maxChars=").append(budget.maxChars())
                .append(" 容忍=").append(budget.toleranceChars())
                .append(" overlap=").append(budget.overlapChars())
                .append(" rowsPerChunk=").append(budget.rowsPerChunk())
                .append("，共 ").append(chunks.size()).append(" 块\n\n")
                .append("| # | 章节路径 | 展示长度 | 检索长度 | 超预算 | 资产 | 展示文本预览 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (Chunk c : chunks) {
            List<String> outline = c.metadata().outlinePath();
            report.append("| ").append(c.index())
                    .append(" | ").append(outline.isEmpty() ? "-" : escapeCell(String.join(" / ", outline)))
                    .append(" | ").append(c.content().length())
                    .append(" | ").append(c.embeddingText().length())
                    .append(" | ").append(c.content().length() > budget.maxChars() ? "是" : "")
                    .append(" | ").append(c.metadata().assets().isEmpty() ? "" : c.metadata().assets().size())
                    .append(" | ").append(escapeCell(preview(c.content(), 60)))
                    .append(" |\n");
        }
    }

    private static List<Chunk> chunk(List<Block> blocks, ChunkBudget budget) {
        ChunkingService service = new ChunkingService(new BlockAwareChunkerDispatcher(
                new HeadingHandler(), new ChunkPacker(), CHUNKERS));
        return service.chunk(blocks, budget);
    }

    /**
     * 走真实路由而不是直接 new 目标解析器：手传 MIME 会把整条 (探测 → 注册表) 链路排除在外，
     * 而这条链路上的漏认领足以让 md 静默落到 Tika 兜底、只剩平段落
     */
    private static List<Block> parse(String fixture) {
        byte[] bytes = load(fixture);
        String mime = MimeTypeDetector.detect(bytes, fixture);
        return REGISTRY.require(mime, ParseProfile.FAST)
                .parseStructured(bytes, mime, Map.of("sourceFile", fixture)).blocks();
    }

    private static byte[] load(String fixture) {
        try (InputStream is = ChunkingFixtureTest.class.getResourceAsStream(FIXTURE_DIR + fixture)) {
            if (is == null) {
                throw new IllegalStateException("缺少 fixture：" + FIXTURE_DIR + fixture);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String preview(String text, int limit) {
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /**
     * 竖线与反引号会破坏 markdown 表格与行内代码，导出前统一转义
     */
    private static String escapeCell(String text) {
        return text.replace("|", "\\|").replace("`", "'");
    }
}
