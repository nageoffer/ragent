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

import java.io.InputStream;
import java.util.Map;

/**
 * 文档解析器统一接口
 * <p>
 * 提供文档解析的通用能力，支持多种文档格式（PDF、Word、Markdown 等）
 * 可用于知识库导入、RAG 检索等场景
 */
public interface DocumentParser {

    /**
     * 获取解析器类型标识
     *
     * @return 解析器类型（如 {@link ParserType#TIKA}、{@link ParserType#MARKDOWN}）
     */
    String getParserType();

    /**
     * 解析文档内容（从字节数组）
     *
     * @param content  文档的二进制字节数组
     * @param mimeType 文档的 MIME 类型（可选）
     * @param options  解析选项（可选）
     * @return 解析结果
     */
    default ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        throw new UnsupportedOperationException("parse(byte[], String, Map) not implemented");
    }

    /**
     * 解析文档内容（从输入流）
     *
     * @param stream   文档输入流
     * @param fileName 文件名（用于推断类型）
     * @return 解析后的文本内容
     */
    default String extractText(InputStream stream, String fileName) {
        throw new UnsupportedOperationException("extractText(InputStream, String) not implemented");
    }

    /**
     * 检查是否支持指定的 MIME 类型
     *
     * @param mimeType MIME 类型
     * @return 是否支持
     */
    default boolean supports(String mimeType) {
        return true;
    }
}
