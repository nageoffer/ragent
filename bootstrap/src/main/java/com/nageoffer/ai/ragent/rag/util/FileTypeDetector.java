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

package com.nageoffer.ai.ragent.rag.util;

import java.util.Locale;
import java.util.Map;

/**
 * 文件类型探测器工具类
 * 用于根据文件名或 MIME 类型识别文件类型
 */
public final class FileTypeDetector {

    private static final Map<String, String> EXTENSION_MAP = Map.ofEntries(
            Map.entry("pdf", "pdf"),
            Map.entry("md", "markdown"),
            Map.entry("markdown", "markdown"),
            Map.entry("doc", "doc"),
            Map.entry("docx", "docx")
    );

    private static final Map<String, String> MIME_MAP = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/x-pdf", "pdf"),
            Map.entry("text/markdown", "markdown"),
            Map.entry("text/x-markdown", "markdown"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.ms-word", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
    );

    private FileTypeDetector() {
    }

    public static String detectType(String fileName) {
        return detectType(fileName, null);
    }

    public static String detectType(String fileName, String mimeType) {
        String extension = extractExtension(fileName);
        String typeByExtension = mapExtension(extension);
        if (typeByExtension != null) {
            return typeByExtension;
        }

        String typeByMime = mapMimeType(mimeType);
        if (typeByMime != null) {
            return typeByMime;
        }

        if (!extension.isBlank()) {
            return extension;
        }

        return mimeType == null ? "" : mimeType;
    }

    private static String mapExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        return EXTENSION_MAP.get(extension);
    }

    private static String mapMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        String normalized = normalizeMimeType(mimeType);
        return MIME_MAP.get(normalized);
    }

    private static String normalizeMimeType(String mimeType) {
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        return separator >= 0 ? normalized.substring(0, separator).trim() : normalized;
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int slashIndex = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slashIndex >= 0 && slashIndex + 1 < name.length()) {
            name = name.substring(slashIndex + 1);
        }
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            return "";
        }
        return name.substring(dotIndex + 1).trim().toLowerCase(Locale.ROOT);
    }
}
