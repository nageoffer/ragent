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

import com.example.FineChunker;
import com.example.PDA;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对照 harness：同一份 Markdown 分别走两套切片器，导出 {@code target/external-chunker-comparison.md}
 * <p>
 * 本地：真实解析器（commonmark AST）→ 块级 chunker（语义切分，标题在正文内，预算 1024/128/50）
 * <br>外部：独立仓库 {@code com.nageoffer.ai:rag-chunker} 的 PDA 逐行粗切 → FineChunker 细切（600–1000 字符目标，12% overlap）
 * <p>
 * 不做断言，只把两边的切分结果摊开给人看。
 */
class ExternalChunkerComparisonTest {

    private static final String LOCAL_FIXTURE = "/fixtures/chunking/merchant-manual.md";

    /** rag-chunker 依赖 jar 自带的两份样例文档（外部作者用来验收切片的输入），从 classpath 直接读 */
    private static final String JAR_ANTHROPIC = "/chunkSource/anthropic-chat.md";
    private static final String JAR_INGESTION = "/chunkSource/Ingestion.md";

    private static final List<BlockChunker<?>> CHUNKERS = List.of(
            new HeadingChunker(),
            new ParagraphChunker(),
            new TableChunker(),
            new HtmlTableChunker(),
            new ImageChunker(),
            new CodeChunker(),
            new ListChunker()
    );

    private static final ParserRegistry REGISTRY = new ParserRegistry(List.of(
            new MarkdownDocumentParser(),
            new CsvDocumentParser(),
            new TikaDocumentParser()
    ));

    @Test
    void shouldExportComparisonReportForManualReview() throws IOException {
        StringBuilder report = new StringBuilder("# 本地 vs 外部(PDA) 切片对照\n");
        StringBuilder console = new StringBuilder();

        for (Doc doc : docs()) {
            console.append('\n').append("════════ ").append(doc.name).append(" ════════\n");

            // ── 本地：解析 → 块级切片 ──
            List<Block> blocks = parse(doc);
            List<Chunk> local = chunk(blocks);
            console.append(localStatLine("本地块级(1024)", local, blockHistogram(blocks)));

            // ── 外部：PDA 粗切 → FineChunker 细切 ──
            PDA pda = new PDA().loadSource(doc.text);
            pda.start();
            List<com.example.Chunk> coarse = pda.getCoarseChunks();
            List<com.example.Chunk> fine = new FineChunker().fineChunk(coarse);
            console.append(externalStatLine("外部粗切", coarse));
            console.append(externalStatLine("外部细切", fine));

            report.append("\n## ").append(doc.name)
                    .append("　（").append(doc.text.length()).append(" 字符，")
                    .append(countLines(doc.text)).append(" 行）\n\n");

            appendOverview(report, doc, blocks, local, coarse, fine);
            appendLocalDetail(report, local);
            appendExternalDetail(report, "外部 PDA 细切", fine);
        }

        Files.writeString(Path.of("target", "external-chunker-comparison.md"), report.toString(), StandardCharsets.UTF_8);
        // 控制台给一份紧凑摘要，完整逐块内容在报告文件里
        System.out.println(console);
    }

    // ======================= 数据装载 =======================

    private record Doc(String name, String text, String origin) {
    }

    private static List<Doc> docs() {
        List<Doc> result = new ArrayList<>();
        result.add(new Doc("merchant-manual.md", readClasspath(LOCAL_FIXTURE), "本地 fixture"));
        result.add(new Doc("anthropic-chat.md", readClasspath(JAR_ANTHROPIC), "rag-chunker jar 样例"));
        result.add(new Doc("Ingestion.md", readClasspath(JAR_INGESTION), "rag-chunker jar 样例"));
        return result;
    }

    private static String readClasspath(String resource) {
        try (InputStream is = ExternalChunkerComparisonTest.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("缺少资源：" + resource);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return text.isEmpty() ? 0 : lines + 1;
    }

    // ======================= 本地链路 =======================

    private static List<Block> parse(Doc doc) {
        byte[] bytes = doc.text.getBytes(StandardCharsets.UTF_8);
        String mime = MimeTypeDetector.detect(bytes, doc.name);
        return REGISTRY.require(mime, ParseProfile.FAST)
                .parseStructured(bytes, mime, Map.of("sourceFile", doc.name)).blocks();
    }

    private static List<Chunk> chunk(List<Block> blocks) {
        ChunkingService service = new ChunkingService(new BlockAwareChunkerDispatcher(
                new HeadingHandler(), new ChunkPacker(), CHUNKERS));
        return service.chunk(blocks, ChunkBudget.defaults());
    }

    // ======================= 统计 =======================

    /** 解析产出的 Block 类型直方图：本地切片的结构输入，中文短名。 */
    private static String blockHistogram(List<Block> blocks) {
        Map<String, Integer> histo = new LinkedHashMap<>();
        for (Block b : blocks) {
            String name = b.getClass().getSimpleName().replace("Block", "");
            String label = switch (name) {
                case "Heading" -> "标题";
                case "Paragraph" -> "段落";
                case "Table", "HtmlTable" -> "表格";
                case "Image" -> "图片";
                case "Code" -> "代码";
                case "List" -> "列表";
                default -> name;
            };
            histo.merge(label, 1, Integer::sum);
        }
        return renderHistogram(histo);
    }

    private static String externalHistogram(List<com.example.Chunk> chunks) {
        Map<String, Integer> histo = new LinkedHashMap<>();
        for (com.example.Chunk c : chunks) {
            histo.merge(c.getType(), 1, Integer::sum);
        }
        return renderHistogram(histo);
    }

    private static String renderHistogram(Map<String, Integer> histo) {
        StringBuilder sb = new StringBuilder();
        histo.forEach((type, count) -> sb.append(type).append('×').append(count).append(' '));
        return sb.toString().strip();
    }

    private static String localStatLine(String label, List<Chunk> chunks, String blockHisto) {
        List<Integer> lens = chunks.stream().map(c -> c.content().length()).toList();
        long sections = chunks.stream().map(c -> String.join(" / ", c.metadata().outlinePath())).distinct().count();
        return statLine(label, lens, sections, blockHisto);
    }

    private static String externalStatLine(String label, List<com.example.Chunk> chunks) {
        List<Integer> lens = chunks.stream().map(c -> c.getContent().length()).toList();
        long sections = chunks.stream().map(c -> String.join(" / ", c.getSectionPath())).distinct().count();
        return statLine(label, lens, sections, externalHistogram(chunks));
    }

    private static String statLine(String label, List<Integer> lens, long sections, String histo) {
        if (lens.isEmpty()) {
            return label + "：无块\n";
        }
        int total = lens.stream().mapToInt(Integer::intValue).sum();
        int min = lens.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = lens.stream().mapToInt(Integer::intValue).max().orElse(0);
        int avg = total / lens.size();
        return String.format("  %-8s 块数=%-3d 总字符=%-6d 平均=%-5d 最小=%-4d 最大=%-5d 估算token=%-5d 章节=%-2d  %s%n",
                label, lens.size(), total, avg, min, max, total / 4, sections, histo);
    }

    // ======================= 报告输出 =======================

    private static void appendOverview(StringBuilder report, Doc doc, List<Block> blocks,
                                       List<Chunk> local,
                                       List<com.example.Chunk> coarse,
                                       List<com.example.Chunk> fine) {
        report.append("| 方案 | 块数 | 总字符 | 平均 | 最小 | 最大 | 估算token | 章节去重 | 类型/构成 |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");

        List<Integer> localLens = local.stream().map(c -> c.content().length()).toList();
        List<Integer> coarseLens = coarse.stream().map(c -> c.getContent().length()).toList();
        List<Integer> fineLens = fine.stream().map(c -> c.getContent().length()).toList();
        long localSections = local.stream().map(c -> String.join(" / ", c.metadata().outlinePath())).distinct().count();
        long coarseSections = coarse.stream().map(c -> String.join(" / ", c.getSectionPath())).distinct().count();
        long fineSections = fine.stream().map(c -> String.join(" / ", c.getSectionPath())).distinct().count();

        report.append("| ").append("本地 块级(默认1024)").append(' ')
                .append(row(localLens, localSections, blockHistogram(blocks))).append('\n');
        report.append("| ").append("外部 PDA 粗切").append(' ')
                .append(row(coarseLens, coarseSections, externalHistogram(coarse))).append('\n');
        report.append("| ").append("外部 PDA 细切").append(' ')
                .append(row(fineLens, fineSections, externalHistogram(fine))).append('\n');

        report.append("\n> 原文 ").append(doc.text.length()).append(" 字符。本地 content 是文档原貌（标题按原文位置在正文内）；")
                .append("外部粗切按行把标题剔除进 sectionPath，细切把 IMAGE/LINK/BLOCKQUOTE 并入 NORMAL 文本，"
                        + "同章节相邻块尾部会带 12% overlap，故外部总字符可能大于原文。\n");
    }

    private static String row(List<Integer> lens, long sections, String histo) {
        if (lens.isEmpty()) {
            return "| - | - | - | - | - | - | - |";
        }
        int total = lens.stream().mapToInt(Integer::intValue).sum();
        int min = lens.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = lens.stream().mapToInt(Integer::intValue).max().orElse(0);
        int avg = total / lens.size();
        return "| " + lens.size() + " | " + total + " | " + avg + " | " + min + " | " + max + " | "
                + total / 4 + " | " + sections + " | " + histo + " |";
    }

    private static void appendLocalDetail(StringBuilder report, List<Chunk> chunks) {
        report.append("\n### 本地 块级切片明细（content 为文档原貌，标题在正文内）\n");
        for (Chunk c : chunks) {
            String outline = String.join(" / ", c.metadata().outlinePath());
            report.append("\n`#").append(c.index()).append("  [len=").append(c.content().length())
                    .append("|tok=").append(c.content().length() / 4)
                    .append("|章节=").append(outline.isEmpty() ? "-" : outline).append("]`\n");
            fenced(report, c.content());
        }
    }

    private static void appendExternalDetail(StringBuilder report, String title, List<com.example.Chunk> chunks) {
        report.append("\n### ").append(title).append(" 明细（标题不占内容，仅进 sectionPath）\n");
        for (com.example.Chunk c : chunks) {
            String section = String.join(" / ", c.getSectionPath());
            String type = c.getType();
            String extra = "CODE".equals(type) && c.getCodeLang() != null ? " lang=" + c.getCodeLang() : "";
            report.append("\n`#").append(c.getChunkIndex()).append("  [").append(type).append(extra)
                    .append("|len=").append(c.getContent().length())
                    .append("|tok=").append(c.getTokenEstimate())
                    .append("|章节=").append(section.isEmpty() ? "-" : section).append("]`\n");
            fenced(report, c.getContent());
        }
    }

    /** 内容可能自带 ``` 围栏，外层统一用 4 反引号起止，避免嵌套围栏截断渲染。 */
    private static void fenced(StringBuilder sb, String content) {
        String flat = content.strip();
        sb.append("````\n").append(flat).append("\n````\n");
    }
}
