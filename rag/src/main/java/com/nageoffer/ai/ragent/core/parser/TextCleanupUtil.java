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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 文档解析后的文本规范化
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TextCleanupUtil {

    /**
     * 依次剥 BOM、去行尾空格与制表符、连续三个以上空行压成两个、去首尾空白
     */
    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
                .replace("\uFEFF", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
