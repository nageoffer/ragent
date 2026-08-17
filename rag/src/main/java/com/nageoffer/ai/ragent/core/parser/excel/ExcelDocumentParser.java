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

package com.nageoffer.ai.ragent.core.parser.excel;

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.core.parser.excel.ExcelTableNormalizer.NormalizedTable;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Excel 文档解析器（Apache POI）
 * <p>
 * 单元格规范化交给 {@link ExcelTableNormalizer}：合并单元格展开填充、多行表头展平拼接、超链接内联为
 * {@code [text](url)}、公式求值并在失败时回退缓存值或公式字符串
 */
@Slf4j
@Component
public class ExcelDocumentParser implements DocumentParser {

    public static final String OPT_SOURCE_FILE = "sourceFile";
    public static final String OPT_HEADER_ROWS = "headerRows";

    private static final int DEFAULT_HEADER_ROWS = 1;

    @Override
    public String getParserType() {
        return ParserType.EXCEL_POI.getType();
    }

    /**
     * 快速档承担全部表格类，含 Tika 的两个 Office 家族别名：纯字节探测（无文件名）时 xlsx / doc 都回落到
     * 它们且无法再区分，交给 POI 通用读取
     */
    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(ParseProfile.FAST, Set.of(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/x-tika-msoffice",
                "application/x-tika-ooxml"
        ));
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        String sourceFile = extractString(options);
        int headerRows = extractInt(options);

        List<Block> blocks = new ArrayList<>();
        int totalSheets;

        try (ByteArrayInputStream is = new ByteArrayInputStream(content);
             Workbook workbook = WorkbookFactory.create(is)) {

            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            totalSheets = workbook.getNumberOfSheets();
            for (int i = 0; i < totalSheets; i++) {
                if (workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i)) {
                    log.info("跳过隐藏 sheet[{}]，不纳入解析结果", workbook.getSheetName(i));
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(i);
                blocks.addAll(buildSheetBlocks(sheet, sourceFile, headerRows, formatter, evaluator));
            }
        } catch (Exception e) {
            log.error("Excel 解析失败，MIME 类型: {}, 文件大小: {} bytes", mimeType, content.length, e);
            throw new ServiceException("Excel 解析失败: " + e.getMessage());
        }

        return ParsedDocument.of(blocks, Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "totalSheets", totalSheets,
                // 只数表格：每个 sheet 还额外产一个承载 sheet 名的 HeadingBlock
                "parsedTables", blocks.stream().filter(TableBlock.class::isInstance).count(),
                "headerRows", headerRows
        ));
    }

    /**
     * 规范化 sheet 为单张表，产出 0 或 2 个 Block
     * <p>
     * sheet 名走 {@code HeadingBlock} 而不是自建一套上下文字段：H1 的顶级重置语义正好是 sheet 之间的关系，
     * 交给 {@code HeadingHandler} 维护 outlinePath 后，sheet 名自然落到向量文本前缀、outline 列与 ES outline 字段
     */
    private List<Block> buildSheetBlocks(Sheet sheet, String sourceFile, int headerRows,
                                         DataFormatter formatter, FormulaEvaluator evaluator) {
        NormalizedTable table = ExcelTableNormalizer.normalize(sheet, formatter, evaluator, headerRows);
        if (table.isEmpty()) {
            log.debug("Sheet [{}] 为空，跳过", sheet.getSheetName());
            return List.of();
        }

        Provenance prov = Provenance.ofExcelCell(sourceFile, sheet.getSheetName());
        return List.of(
                new HeadingBlock(prov, 1, sheet.getSheetName()),
                new TableBlock(prov, table.headers(), table.rows())
        );
    }

    private static String extractString(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get(ExcelDocumentParser.OPT_SOURCE_FILE);
        return v == null ? "" : v.toString();
    }

    private static int extractInt(Map<String, Object> options) {
        if (options == null) {
            return ExcelDocumentParser.DEFAULT_HEADER_ROWS;
        }
        Object v = options.get(ExcelDocumentParser.OPT_HEADER_ROWS);
        if (v == null) {
            return ExcelDocumentParser.DEFAULT_HEADER_ROWS;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return ExcelDocumentParser.DEFAULT_HEADER_ROWS;
        }
    }
}
