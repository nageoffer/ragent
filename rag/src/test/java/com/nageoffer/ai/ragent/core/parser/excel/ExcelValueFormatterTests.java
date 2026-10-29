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

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 公式 cell 在无 evaluator 时走缓存值回退，缓存的是数值、字符串还是日期格式，产出都应是「值」而不是公式串
 */
class ExcelValueFormatterTests {

    private final DataFormatter formatter = new DataFormatter();

    @Test
    void shouldReadCachedNumericResultInsteadOfFormulaText() throws Exception {
        try (Workbook workbook = new HSSFWorkbook()) {
            Cell cell = cachedFormulaCell(workbook, "1+2", 3.0, null);
            assertEquals("3", ExcelValueFormatter.format(cell, formatter, null));
        }
    }

    @Test
    void shouldKeepCellNumberFormatWhenFormattingCachedNumericResult() throws Exception {
        try (Workbook workbook = new HSSFWorkbook()) {
            Cell cell = cachedFormulaCell(workbook, "TODAY()", 45000.0, "yyyy-mm-dd");
            assertEquals("2023-03-15", ExcelValueFormatter.format(cell, formatter, null));
        }
    }

    @Test
    void shouldReadCachedStringResultInsteadOfFormulaText() throws Exception {
        try (Workbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("sheet");
            Cell cell = sheet.createRow(0).createCell(0);
            cell.setCellFormula("CONCATENATE(\"a\",\"b\")");
            cell.setCellValue("ab");
            assertEquals("ab", ExcelValueFormatter.format(cell, formatter, null));
        }
    }

    /**
     * 造一个带缓存结果的公式 cell：{@code setCellValue} 在公式 cell 上写入的是缓存结果而非字面量
     */
    private static Cell cachedFormulaCell(Workbook workbook, String formula, double cachedValue, String dataFormat) {
        Sheet sheet = workbook.createSheet("sheet");
        Cell cell = sheet.createRow(0).createCell(0);
        cell.setCellFormula(formula);
        cell.setCellValue(cachedValue);
        if (dataFormat != null) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat(dataFormat));
            cell.setCellStyle(style);
        }
        return cell;
    }
}
