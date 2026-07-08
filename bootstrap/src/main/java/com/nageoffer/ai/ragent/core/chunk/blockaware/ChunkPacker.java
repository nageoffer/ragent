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

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chunk 打包器（block-aware 分块后处理）
 * <p>
 * 各类型 chunker 只负责"单个 block 内"的切分, 天然是"只拆不并": 一个短段落、一个短列表都会各自成块,
 * 512 体量预算只当上限、从不当目标, 于是结构清晰的小文档被切成一堆碎块
 * <p>
 * 本打包器在 dispatch 产出的有序 chunk 上做一次贪心合并: 把相邻的<b>可流动块</b>(段落 / 列表 / 图片)累加到接近
 * {@code maxChars} 再落块, 遇到<b>原子块</b>(表格 / 代码)或已达体量上限的大块就断开, 使块大小真正贴合预算
 */
@Component
public class ChunkPacker {

    /**
     * 可合并的块类型: 文本流与图片引用, 相邻小块可拼到同一 chunk; 表格 / 代码等结构完整块保持原子
     */
    private static final Set<String> MERGEABLE_TYPES = Set.of("PARAGRAPH", "LIST", "IMAGE");
    /**
     * 合并时块间分隔符, 保留段落 / 列表边界
     */
    private static final String SEPARATOR = "\n\n";

    /**
     * 贪心打包: 相邻可合并 chunk 累加至 maxChars, 原子块与超限大块原样保留, 最后重排 index
     * <p>
     * 断块时以<b>块级重叠</b>衔接: 用上一块尾部若干个<b>完整</b>可合并块(累计不超过 overlapChars 预算)作为下一块的起点,
     * 既复现跨块上下文、又不切碎段落 / 列表项 / 标题. 重叠计入 maxChars 预算, 保证块体量不超上限
     *
     * @param chunks       dispatch 产出的有序 chunk
     * @param maxChars     单块体量预算(合并上限)
     * @param overlapChars 块级重叠预算(尾部完整块的累计字符上限, 0 表示不重叠)
     * @return 打包后的 chunk, index 从 0 单调递增
     */
    public List<VectorChunk> pack(List<VectorChunk> chunks, int maxChars, int overlapChars) {
        if (chunks == null || chunks.size() <= 1) {
            return chunks == null ? List.of() : chunks;
        }

        List<VectorChunk> result = new ArrayList<>();
        List<VectorChunk> buffer = new ArrayList<>();
        int bufferLen = 0;

        for (VectorChunk c : chunks) {
            // 原子块 / 已超预算的大块: 先冲刷缓冲区, 自身原样落块并断开合并链(原子块两侧不做重叠)
            if (!isMergeable(c, maxChars)) {
                flush(buffer, result);
                buffer.clear();
                bufferLen = 0;
                result.add(c);
                continue;
            }

            int addLen = contentLength(c);
            int sepLen = buffer.isEmpty() ? 0 : SEPARATOR.length();
            // 再加会超预算: 先冲刷, 用尾部完整块作为重叠起点(留出容纳当前块的余量, 保证不超 maxChars)
            if (!buffer.isEmpty() && bufferLen + sepLen + addLen > maxChars) {
                flush(buffer, result);
                // 重叠预算需同时扣除当前块与其前面的分隔符长度，否则合并结果可能超出 maxChars
                buffer = overlapTail(buffer, Math.min(overlapChars, maxChars - addLen - SEPARATOR.length()));
                bufferLen = bufferedLength(buffer);
            }
            bufferLen += (buffer.isEmpty() ? 0 : SEPARATOR.length()) + addLen;
            buffer.add(c);
        }
        flush(buffer, result);

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setIndex(i);
        }
        return result;
    }

    /**
     * 取缓冲区尾部若干完整块作为下一块的重叠起点: 从后往前累加, 累计字符不超 budget, 保持原顺序
     *
     * @return 可变新列表(可能为空); 元素为原 chunk 引用(内容在下一块中被复现)
     */
    private static List<VectorChunk> overlapTail(List<VectorChunk> buffer, int budget) {
        List<VectorChunk> carry = new ArrayList<>();
        if (budget <= 0) {
            return carry;
        }
        int len = 0;
        for (int i = buffer.size() - 1; i >= 0; i--) {
            int sep = carry.isEmpty() ? 0 : SEPARATOR.length();
            int next = len + sep + contentLength(buffer.get(i));
            if (next > budget) {
                break;
            }
            carry.add(0, buffer.get(i));
            len = next;
        }
        return carry;
    }

    /**
     * 缓冲区当前拼接长度(含块间分隔符)
     */
    private static int bufferedLength(List<VectorChunk> buffer) {
        int len = 0;
        for (int i = 0; i < buffer.size(); i++) {
            len += (i == 0 ? 0 : SEPARATOR.length()) + contentLength(buffer.get(i));
        }
        return len;
    }

    /**
     * 可合并: 类型属于可流动块(文本 / 图片), 且自身未达体量上限(超限大块是切分产物, 视为原子, 不再粘连)
     * <p>
     * 图片一律并入相邻上下文(不分有无描述): 图与它的前导语 / 解释文字同块, 检索命中即带图, 也不割裂正文
     */
    private static boolean isMergeable(VectorChunk c, int maxChars) {
        return MERGEABLE_TYPES.contains(c.getBlockType()) && contentLength(c) < maxChars;
    }

    /**
     * 冲刷缓冲区: 空则跳过, 单块原样搬运, 多块合并成一个 chunk
     */
    private static void flush(List<VectorChunk> buffer, List<VectorChunk> result) {
        if (buffer.isEmpty()) {
            return;
        }
        if (buffer.size() == 1) {
            result.add(buffer.get(0));
            return;
        }
        result.add(merge(buffer));
    }

    /**
     * 合并多块: 内容按 SEPARATOR 拼接, outlinePath 取最长公共前缀(退化到共同祖先章节),
     * sourceBlockIds / assets 去重并集(无描述图片并入正文后其 AssetRef 仍随块留存),
     * blockType 同质则保留、异质归为 PARAGRAPH(仍是纯文本流)
     */
    private static VectorChunk merge(List<VectorChunk> buffer) {
        StringBuilder content = new StringBuilder();
        StringBuilder embeddingText = new StringBuilder();
        boolean hasExplicitEmbeddingText = false;
        String sectionContext = null;
        Set<String> sourceBlockIds = new LinkedHashSet<>();
        List<AssetRef> assets = new ArrayList<>();
        String blockType = buffer.get(0).getBlockType();
        boolean homogeneous = true;
        for (VectorChunk c : buffer) {
            if (!content.isEmpty()) {
                content.append(SEPARATOR);
            }
            content.append(c.getContent() == null ? "" : c.getContent());
            // embeddingText 不能在合并时丢弃：图片块特意用「无 URL 噪声」的描述文本做向量化，
            // 丢弃后 embedding 会退化为携带原始 URL 的 content。任一块显式提供 embeddingText
            // 时，合并块按「显式值优先、否则回退 content」逐块拼接
            String effectiveEmbedding = c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank()
                    ? c.getEmbeddingText()
                    : c.getContent();
            if (c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank()) {
                hasExplicitEmbeddingText = true;
            }
            if (effectiveEmbedding != null && !effectiveEmbedding.isBlank()) {
                if (!embeddingText.isEmpty()) {
                    embeddingText.append(SEPARATOR);
                }
                embeddingText.append(effectiveEmbedding);
            }
            // sectionContext 取首个非空值（合并块与 outlinePath 一样归属共同上级章节）
            if (sectionContext == null && c.getSectionContext() != null && !c.getSectionContext().isBlank()) {
                sectionContext = c.getSectionContext();
            }
            if (c.getSourceBlockIds() != null) {
                sourceBlockIds.addAll(c.getSourceBlockIds());
            }
            if (c.getAssets() != null) {
                assets.addAll(c.getAssets());
            }
            if (!java.util.Objects.equals(blockType, c.getBlockType())) {
                homogeneous = false;
            }
        }
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .content(content.toString())
                .embeddingText(hasExplicitEmbeddingText ? embeddingText.toString() : null)
                .sectionContext(sectionContext)
                .blockType(homogeneous ? blockType : "PARAGRAPH")
                .outlinePath(commonPrefix(buffer))
                .sourceBlockIds(new ArrayList<>(sourceBlockIds))
                .assets(assets)
                .build();
    }

    /**
     * 多块 outlinePath 的最长公共前缀: 合并块横跨若干小节时, 归属其共同上级章节
     */
    private static List<String> commonPrefix(List<VectorChunk> buffer) {
        List<String> prefix = new ArrayList<>(safePath(buffer.get(0)));
        for (int i = 1; i < buffer.size() && !prefix.isEmpty(); i++) {
            List<String> path = safePath(buffer.get(i));
            int keep = 0;
            while (keep < prefix.size() && keep < path.size()
                    && prefix.get(keep).equals(path.get(keep))) {
                keep++;
            }
            prefix.subList(keep, prefix.size()).clear();
        }
        return prefix;
    }

    private static List<String> safePath(VectorChunk c) {
        return c.getOutlinePath() == null ? List.of() : c.getOutlinePath();
    }

    private static int contentLength(VectorChunk c) {
        return StringUtils.hasText(c.getContent()) ? c.getContent().length() : 0;
    }
}
