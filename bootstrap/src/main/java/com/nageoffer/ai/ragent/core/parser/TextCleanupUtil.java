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

package com.nageoffer.ai.ragent.core.parser;

/**
 * 文本清理工具类
 * <p>
 * 提供统一的文本清理逻辑，用于文档解析后的文本规范化
 */
public final class TextCleanupUtil {

    private TextCleanupUtil() {
    }

    /**
     * 清理文本内容
     * <p>
     * 执行以下清理操作：
     * 1. 移除 BOM 标记（\uFEFF）
     * 2. 移除行尾多余的空格和制表符
     * 3. 压缩连续的空行（3个以上压缩为2个）
     * 4. 去除首尾空白
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
                // 移除 BOM 标记
                .replace("\uFEFF", "")
                // 移除行尾的空格和制表符
                .replaceAll("[ \\t]+\\n", "\n")
                // 压缩连续的空行（3个以上压缩为2个）
                .replaceAll("\\n{3,}", "\n\n")
                // 去除首尾空白
                .trim();
    }

    /**
     * 清理文本内容（自定义规则）
     *
     * @param text                原始文本
     * @param removeBOM           是否移除 BOM
     * @param trimTrailingSpaces  是否移除行尾空格
     * @param compressEmptyLines  是否压缩空行
     * @param maxConsecutiveLines 最多保留的连续空行数
     * @return 清理后的文本
     */
    public static String cleanup(String text,
                                 boolean removeBOM,
                                 boolean trimTrailingSpaces,
                                 boolean compressEmptyLines,
                                 int maxConsecutiveLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;

        if (removeBOM) {
            result = result.replace("\uFEFF", "");
        }

        if (trimTrailingSpaces) {
            result = result.replaceAll("[ \\t]+\\n", "\n");
        }

        if (compressEmptyLines && maxConsecutiveLines > 0) {
            String pattern = "\\n{" + (maxConsecutiveLines + 1) + ",}";
            String replacement = "\n".repeat(maxConsecutiveLines);
            result = result.replaceAll(pattern, replacement);
        }

        return result.trim();
    }
}
