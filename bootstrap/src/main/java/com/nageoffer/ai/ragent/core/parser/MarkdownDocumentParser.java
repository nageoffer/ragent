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

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Markdown 文档解析器
 * <p>
 * 专门处理 Markdown 格式的文档
 * 保留原始 Markdown 格式，不进行 HTML 转换
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public String getParserType() {
        return ParserType.MARKDOWN.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }

        String text = new String(content, StandardCharsets.UTF_8);
        return ParseResult.ofText(text);
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("解析 Markdown 文件失败: " + fileName, e);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && (
                mimeType.equals("text/markdown") ||
                        mimeType.equals("text/x-markdown") ||
                        mimeType.equals("text/plain")
        );
    }
}
