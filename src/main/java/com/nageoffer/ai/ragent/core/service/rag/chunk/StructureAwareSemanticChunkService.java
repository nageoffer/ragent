package com.nageoffer.ai.ragent.core.service.rag.chunk;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知的语义分块（Markdown 友好版）
 * - 绝不改写文本，只在“块”边界切分；
 * - 块类型：Heading、Paragraph（空行分段）、CodeFence（```...```）、Atomic（整行 ![]()/[]()）；
 * - 通过 min/target/max 预算控制 chunk 大小；
 * - 默认无 overlap，如需可打开 kb.chunk.semantic.overlapChars。
 */
@Slf4j
@Service("structureAwareSemanticChunkService")
public class StructureAwareSemanticChunkService implements ChunkService {

    // ----------- 可调参数（字符预算） -----------
    /**
     * 理想 chunk 字符数（尽量靠近）
     */
    @Value("${kb.chunk.semantic.targetChars:1400}")
    private int targetChars;

    /**
     * 硬上限：超过则尽量在前一块截断；若当前块太小则允许轻微超限
     */
    @Value("${kb.chunk.semantic.maxChars:1800}")
    private int maxChars;

    /**
     * 最小下限：小于该阈值会优先与后续块合并
     */
    @Value("${kb.chunk.semantic.minChars:600}")
    private int minChars;

    /**
     * 邻接重叠字符数（默认 0：不开启，以避免重复噪声）；需要时可设置为 120~200
     */
    @Value("${kb.chunk.semantic.overlapChars:0}")
    private int overlapChars;

    // ----------- 识别用正则 -----------
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern ATOMIC_IMAGE = Pattern.compile("^!\\[[^\\]]*]\\([^\\)]+\\)(?:\\s*\"[^\"]*\")?\\s*$");
    private static final Pattern ATOMIC_LINK = Pattern.compile("^\\[[^\\]]+]\\([^\\)]+\\)\\s*$");

    // ----------- 主流程 -----------
    @Override
    public List<Chunk> split(String text) {
        if (text == null || text.isEmpty()) return List.of();

        // 1) 扫描成“块”（记录原文的 start/end 下标，确保输出 substring 完全等于原文）
        List<Block> blocks = segmentToBlocks(text);

        if (blocks.isEmpty()) {
            return List.of(new Chunk(0, text)); // 极端兜底：整体作为一个块
        }

        // 2) 依据 min/target/max 打包成 chunk（只在块边界切分）
        List<int[]> ranges = packBlocksToChunks(blocks, text.length());

        // 3)（可选）加入重叠：为保持“只在块边界切分”，这里不在中间加重叠，若开启 overlap，仅复制“上一 chunk 的尾部全文子串”到下一 chunk 的开头
        List<Chunk> out = materialize(text, ranges, overlapChars);

        // 编号从 0 递增
        for (int i = 0; i < out.size(); i++) {
            out.set(i, new Chunk(i, out.get(i).getContent()));
        }
        return out;
    }

    // ----------- 块模型 -----------
    @Getter
    @ToString
    @AllArgsConstructor
    private static class Block {
        enum Kind {HEADING, CODE, ATOMIC, PARA}

        final Kind kind;
        final int start;   // 在原文中的起始（含）
        final int end;     // 在原文中的结束（不含）
    }

    // ----------- 1) 线性扫描生成块 -----------
    private List<Block> segmentToBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int n = text.length();
        int pos = 0;

        boolean inFence = false;
        int fenceStart = -1;

        boolean inPara = false;
        int paraStart = -1;

        while (pos < n) {
            int lineEnd = indexOfNl(text, pos);
            // [pos, lineEnd) 不含换行字符；lineEndNl = 包含换行（若有）
            int lineEndNl = lineEnd < n && text.charAt(lineEnd) == '\n' ? lineEnd + 1 : lineEnd;
            String line = text.substring(pos, lineEnd);

            String trimmed = trimRightKeepLeft(line); // 不改左侧空白，保留原貌；右侧空白不影响判断

            if (!inFence && CODE_FENCE.matcher(trimmed).matches()) {
                // 先把正在积累的段落收尾
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                // 进入代码围栏
                inFence = true;
                fenceStart = pos;
                pos = lineEndNl;
                continue;
            }

            if (inFence) {
                // 直到遇到 fence 结束行
                if (CODE_FENCE.matcher(trimmed).matches()) {
                    // 包含结束 fence 行
                    blocks.add(new Block(Block.Kind.CODE, fenceStart, lineEndNl));
                    inFence = false;
                }
                pos = lineEndNl;
                continue;
            }

            // 空行 => 段落边界
            if (trimmed.isEmpty()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                // 空行本身并入前一块或下一块？——保持原貌：把空行并入前一块（若无前一块，则作为 0 长度过渡）
                pos = lineEndNl;
                continue;
            }

            // 标题/原子行（图片/链接）都作为独立块
            if (HEADING.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.HEADING, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }
            if (ATOMIC_IMAGE.matcher(trimmed).matches() || ATOMIC_LINK.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.ATOMIC, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }

            // 其他：并入当前段落
            if (!inPara) {
                inPara = true;
                paraStart = pos;
            }
            pos = lineEndNl;
        }

        // 收尾
        if (inFence) {
            // 未闭合 fence：将剩余部分作为 CODE（保持原样）
            blocks.add(new Block(Block.Kind.CODE, fenceStart, n));
        } else if (inPara) {
            blocks.add(new Block(Block.Kind.PARA, paraStart, n));
        }
        return coalesceTrailingBlanks(blocks, text);
    }

    // 合并“块尾部的若干空行”到块内部，避免单独产生空白块（保持原文不变，只是归属到前块）
    private List<Block> coalesceTrailingBlanks(List<Block> blocks, String text) {
        if (blocks.isEmpty()) return blocks;
        List<Block> out = new ArrayList<>();
        Block prev = blocks.get(0);
        for (int i = 1; i < blocks.size(); i++) {
            Block cur = blocks.get(i);
            if (isAllBlank(text, prev.end, cur.start)) {
                // 把中间空白并入 prev，但别丢掉 cur
                prev = new Block(prev.kind, prev.start, cur.start);
            }
            // 无论是否并入空白，prev 都该进结果，然后向前推进
            out.add(prev);
            prev = cur;
        }
        out.add(prev);
        return out;
    }

    // ----------- 2) 打包成 chunk（仅在块边界切） -----------
    private List<int[]> packBlocksToChunks(List<Block> blocks, int textLen) {
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            int chunkStart = blocks.get(i).start;
            int chunkEnd = blocks.get(i).end; // 不含
            int size = chunkEnd - chunkStart;

            int j = i + 1;
            while (j < blocks.size()) {
                Block b = blocks.get(j);
                int afterAdd = (b.end - chunkStart); // 等同于 size + nextSize + 中间空白（已包含）

                if (afterAdd <= maxChars) {
                    // 还能加
                    chunkEnd = b.end;
                    size = afterAdd;
                    j++;
                } else {
                    // 超过 max：若当前 size < min，则“忍一次超限”，把这个块也吸进去（保证不要太小）
                    if (size < minChars) {
                        chunkEnd = b.end;
                        size = afterAdd;
                        j++;
                    }
                    break;
                }
            }

            ranges.add(new int[]{chunkStart, chunkEnd});
            i = j;
        }

        // 若最后一个 chunk 明显过小，尝试与前一个合并（仍不跨越 max 过多）
        if (ranges.size() >= 2) {
            int[] last = ranges.get(ranges.size() - 1);
            if (last[1] - last[0] < Math.min(minChars, targetChars / 2)) {
                int[] prev = ranges.get(ranges.size() - 2);
                if (last[1] - prev[0] <= maxChars * 2) { // 放宽一下，尽量合并到可接受大小
                    prev[1] = last[1];
                    ranges.remove(ranges.size() - 1);
                }
            }
        }
        return ranges;
    }

    // ----------- 3) 物化为 Chunk，必要时追加 overlap（复制原文尾部） -----------
    private List<Chunk> materialize(String text, List<int[]> ranges, int overlap) {
        if (ranges.isEmpty()) return List.of();
        List<Chunk> out = new ArrayList<>();
        String prevTail = null;

        for (int k = 0; k < ranges.size(); k++) {
            int s = ranges.get(k)[0];
            int e = ranges.get(k)[1];
            String body = text.substring(s, e);
            if (overlap > 0 && prevTail != null && !prevTail.isEmpty()) {
                body = prevTail + body;
            }
            out.add(new Chunk(k, body));

            // 计算下一块的 overlap 尾部（完全来自本 chunk 原文结尾）
            if (overlap > 0) {
                String tail = tailByChars(text.substring(s, e), overlap);
                prevTail = tail;
            }
        }
        return out;
    }

    // ----------- 小工具 -----------
    private int indexOfNl(String s, int from) {
        int p = s.indexOf('\n', from);
        return p < 0 ? s.length() : p;
    }

    private String trimRightKeepLeft(String s) {
        int r = s.length();
        while (r > 0 && Character.isWhitespace(s.charAt(r - 1)) && s.charAt(r - 1) != '\n' && s.charAt(r - 1) != '\r') {
            r--;
        }
        return s.substring(0, r);
    }

    private boolean isAllBlank(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!(c == ' ' || c == '\t' || c == '\r' || c == '\n')) return false;
        }
        return true;
    }

    private String tailByChars(String s, int n) {
        if (n <= 0) return "";
        int len = s.length();
        return len <= n ? s : s.substring(len - n);
    }
}
