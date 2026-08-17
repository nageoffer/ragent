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

package com.nageoffer.ai.ragent.core.chunk.blockaware;

import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格 chunker：按 key-value 渲染长度累加到预算，每块都带完整表头，单行超预算时整行原子成块
 * <p>
 * {@code rowsPerChunk} 只作硬上限，兼顾宽表不超嵌入上限、窄表不过度碎片化；展示文本是完整
 * markdown 表格，向量文本改用 {@code 列名: 值}，因为 markdown 表格靠位置对齐列名与值、嵌入模型
 * 读不懂位置；表头不拼进向量文本，KV 正文已逐格自带列名，重复前缀会把同一张表各块的向量朝同一
 * 方向拉、压缩块间距离，表身份由 sheet 名经章节路径承载
 */
@Component
public class TableChunker implements BlockChunker<TableBlock> {

    @Override
    public Class<TableBlock> blockType() {
        return TableBlock.class;
    }

    @Override
    public List<ChunkDraft> chunk(TableBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        List<String> headers = block.headers() == null ? List.of() : block.headers();
        List<List<String>> rows = block.rows() == null ? List.of() : block.rows();
        if (headers.isEmpty() && rows.isEmpty()) {
            return List.of();
        }

        // 预算只量 KV 行本身，刻意不扣装配器追加的章节路径前缀：真去扣，深层章节会把可用预算逼近 0，
        // 退化成每行一块、每块大半是逐字相同的前缀
        int maxRows = Math.max(1, ctx.budget().rowsPerChunk());
        // 整张表撑得住容忍上限就不切，切开后每块虽重带表头，跨块的行间对比仍然做不了
        int budget = rows.size() <= maxRows
                && renderKeyValueRows(headers, rows).length() <= ctx.budget().toleranceChars()
                ? ctx.budget().toleranceChars()
                : Math.max(1, ctx.budget().maxChars());

        List<ChunkDraft> result = new ArrayList<>();

        if (rows.isEmpty()) {
            result.add(buildDraft(block, ctx, headers, List.of()));
            return result;
        }

        // 贪心累加：超硬上限或（非空且加入下一行会超预算）则先落块
        List<List<String>> group = new ArrayList<>();
        int groupCost = 0;
        for (List<String> row : rows) {
            int rowCost = renderKeyValueRow(headers, row).length();
            boolean overCap = group.size() >= maxRows;
            boolean overBudget = !group.isEmpty() && groupCost + rowCost > budget;
            if (overCap || overBudget) {
                result.add(buildDraft(block, ctx, headers, group));
                group = new ArrayList<>();
                groupCost = 0;
            }
            group.add(row);
            groupCost += rowCost;
        }
        result.add(buildDraft(block, ctx, headers, group));
        return ChunkDraft.pieces(result);
    }

    private ChunkDraft buildDraft(TableBlock block, ChunkContext ctx, List<String> headers, List<List<String>> rows) {
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(block.provenance())
                .build();
        // 章节路径由装配器统一拼进向量文本，此处只给 key-value 正文，避免重复前缀
        return ChunkDraft.of(renderMarkdownTable(headers, rows), renderKeyValueRows(headers, rows), metadata);
    }

    private String renderKeyValueRows(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            String line = renderKeyValueRow(headers, row);
            if (line.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 单行渲染成 {@code 列名: 值}，"; " 拼接、跳过空值、整行空返回空串；同时用作预算切分的行体量度量
     */
    private String renderKeyValueRow(List<String> headers, List<String> row) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < row.size(); c++) {
            String value = row.get(c);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String key = c < headers.size() ? headers.get(c) : "";
            if (!line.isEmpty()) {
                line.append("; ");
            }
            if (!key.isEmpty()) {
                line.append(oneLine(key)).append(": ");
            }
            line.append(oneLine(value));
        }
        return line.toString();
    }

    /**
     * 把 cell 内换行压成空格：key 与 value 之间夹一个断行会影响检索
     */
    private static String oneLine(String text) {
        return text.replaceAll("\\r\\n|\\r|\\n", " ");
    }

    private String renderMarkdownTable(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        appendSeparator(sb, headers.size());
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, List<String> cells) {
        sb.append('|');
        for (String cell : cells) {
            sb.append(' ').append(sanitizeCell(cell)).append(" |");
        }
        sb.append('\n');
    }

    /**
     * 清洗 cell 以适配 markdown 表格语法
     * <p>
     * 单元格内换行（Excel Alt+Enter）转 {@code <br>}，裸换行会从中间截断表格行、使整块退化成普通段落；
     * 竖线转义，cell 内的字面 {@code |} 会被误判为列分隔
     */
    private String sanitizeCell(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        return cell.replace("|", "\\|").replaceAll("\\r\\n|\\r|\\n", "<br>");
    }

    private void appendSeparator(StringBuilder sb, int colCount) {
        sb.append('|');
        sb.append("---|".repeat(Math.max(0, colCount)));
        sb.append('\n');
    }

}
