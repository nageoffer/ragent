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

package com.nageoffer.ai.ragent.ingestion.strategy.parser;

import com.nageoffer.ai.ragent.ingestion.domain.context.StructuredDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Markdown 文档解析器实现类
 * 负责将 Markdown 格式的内容解析为结构化文档对象
 */
@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public String getParserType() {
        return "MARKDOWN";
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return new ParseResult("", null);
        }
        String text = new String(content, StandardCharsets.UTF_8);
        StructuredDocument doc = StructuredDocument.builder().text(text).build();
        return new ParseResult(text, doc);
    }
}
