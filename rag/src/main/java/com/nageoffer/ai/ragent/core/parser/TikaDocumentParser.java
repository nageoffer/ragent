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

import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apache Tika 解析器：纯文本类格式的兜底，覆盖 HTML / JSON / XML / RTF 及未被更专门的解析器认领的
 * {@code text/*}；复杂版面（PDF / Word / PPT）走 MinerU，表格走 POI / CSV，markdown 走 commonmark
 * <p>
 * 长尾靠 {@code text/*} 通配键覆盖，精确键一律优先于通配键，因此 {@code text/csv}、
 * {@code text/plain}、{@code text/x-web-markdown}（Tika 探测 {@code .md} 的产出）都不会落到这里
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    static {
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setExtractUniqueInlineImagesOnly(true);
    }

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    /**
     * 结构化解析：Tika 输出是平文本，无章节标题 / 表格结构可挖，故按 {@code \n\n+} 空行分段，只产 ParagraphBlock
     */
    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        String text;
        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            text = TIKA.parseToString(is);
            text = TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("Tika 结构化解析失败，MIME 类型: {}", mimeType, e);
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        List<Block> blocks = new ArrayList<>();
        for (String segment : text.split("\\n{2,}")) {
            String trimmed = segment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            blocks.add(new ParagraphBlock(prov, trimmed));
        }
        return ParsedDocument.of(blocks, Map.of("parser", getParserType(), "mimeType", mimeType == null ? "" : mimeType));
    }

    private String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    /**
     * 精确键覆盖已声明支持的格式，{@code text/*} 通配只兜未声明的长尾；刻意不认领 image 与未知 MIME，
     * 认不出来就报错，不要兜底产出垃圾文本
     */
    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(ParseProfile.FAST, Set.of(
                "text/*",
                "text/html",
                "application/json",
                "application/xml",
                "application/xhtml+xml",
                "application/rtf"
        ));
    }
}
