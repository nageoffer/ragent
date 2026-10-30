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

package com.nageoffer.ai.ragent.core.chunk.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 切分不得把代理对（surrogate pair）腰斩
 * <p>
 * 补充平面字符（emoji、生僻汉字扩展 B 区等）在 {@code String} 里占两个 char，按下标切分若落在两个 char 之间，
 * 片段首尾就会出现孤立高代理 / 孤立低代理，构成非法 UTF-16 序列
 */
class TextSplitterSurrogatePairTest {

    /**
     * U+1F600，两个 char（高代理 D83D + 低代理 DE00）
     */
    private static final String GRIN = "\uD83D\uDE00";

    @Test
    void chunkBoundaryDoesNotLandBetweenHighAndLowSurrogate() {
        // 9 个 ascii + emoji，预算 10 恰好切在高代理之后，原实现会产出以孤立高代理结尾的首片
        String text = "aaaaaaaaa" + GRIN + "bbbbbbbbbb";

        List<String> pieces = TextSplitter.split(text, 10, 0);

        assertAllPiecesWellFormed(pieces);
    }

    @Test
    void overlapStartDoesNotBeginOnLowSurrogate() {
        // 4 个 ascii + emoji + 6 个 ascii，预算 7、重叠 2，下一片起点正好落在低代理上
        String text = "aaaa" + GRIN + "bbbbbb";

        List<String> pieces = TextSplitter.split(text, 7, 2);

        assertAllPiecesWellFormed(pieces);
    }

    @Test
    void allPiecesOfMixedChineseTextAreWellFormed() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("商保通第").append(i).append("条说明").append(GRIN).append("。");
        }

        List<String> pieces = TextSplitter.split(sb.toString(), 37, 9);

        assertAllPiecesWellFormed(pieces);
        assertTrue(pieces.size() > 1, "文本应被切分");
    }

    @Test
    void singleCharacterBudgetStillEmitsWholeSurrogatePair() {
        // 预算 1 时无处可退，只能把整个代理对留在同一片，不能退化成空片或死循环
        List<String> pieces = TextSplitter.split(GRIN, 1, 0);

        assertEquals(1, pieces.size());
        assertEquals(GRIN, pieces.get(0));
    }

    @Test
    void splitDoesNotDropContent() {
        // 退让切点会移动边界，但不得吞掉任何字符：拼回原文（去重叠）应覆盖全部预算外字符
        String text = "甲" + GRIN + "乙" + GRIN + "丙" + GRIN + "丁";

        List<String> pieces = TextSplitter.split(text, 4, 1);

        assertAllPiecesWellFormed(pieces);
        StringBuilder joined = new StringBuilder();
        for (String piece : pieces) {
            joined.append(piece.strip());
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                assertTrue(joined.indexOf(String.valueOf(c)) >= 0, "字符丢失: " + c);
            }
        }
    }

    private static void assertAllPiecesWellFormed(List<String> pieces) {
        assertFalse(pieces.isEmpty(), "切分结果不应为空");
        for (int index = 0; index < pieces.size(); index++) {
            String piece = pieces.get(index);
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= piece.length() || !Character.isLowSurrogate(piece.charAt(i + 1))) {
                        fail("第 " + index + " 片在偏移 " + i + " 处出现孤立高代理，片段: "
                                + describe(piece));
                    }
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    fail("第 " + index + " 片在偏移 " + i + " 处出现孤立低代理，片段: "
                            + describe(piece));
                }
            }
        }
    }

    private static String describe(String piece) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < piece.length(); i++) {
            sb.append(String.format("\\u%04X", (int) piece.charAt(i)));
        }
        return sb.toString();
    }
}
