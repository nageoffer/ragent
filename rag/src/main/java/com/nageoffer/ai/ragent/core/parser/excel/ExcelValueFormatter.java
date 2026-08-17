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

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Excel cell 值格式化工具
 * <p>
 * 处理优先级：
 * <ol>
 *   <li>空 cell → 空字符串</li>
 *   <li>公式 cell：用 evaluator 求值 → 失败回退到缓存值 → 再失败回退到原始公式字符串</li>
 *   <li>其他 cell：DataFormatter 直接格式化（覆盖数值、日期、布尔）</li>
 * </ol>
 */
@Slf4j
public final class ExcelValueFormatter {

    private ExcelValueFormatter() {
    }

    /**
     * 格式化 cell 为字符串
     *
     * @param cell      cell 实例，可空（返回空字符串）
     * @param formatter DataFormatter 实例（线程不安全，调用方持有）
     * @param evaluator 公式求值器，可空（无 evaluator 时公式 cell 走缓存值或公式字符串）
     * @return 格式化后的字符串（已 trim）
     */
    public static String format(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }

        if (cell.getCellType() == CellType.FORMULA) {
            return formatFormulaCell(cell, formatter, evaluator);
        }

        return formatter.formatCellValue(cell).trim();
    }

    private static String formatFormulaCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        // 第 1 选择：通过 evaluator 求值
        if (evaluator != null) {
            try {
                return formatter.formatCellValue(cell, evaluator).trim();
            } catch (Exception e) {
                log.warn("公式 cell evaluate 失败，回退到缓存值。cell: {}", describe(cell), e);
            }
        }

        // 第 2 选择：直接读 cached formula result（POI 5.x 默认会缓存上次写入时的结果）
        try {
            CellType cachedType = cell.getCachedFormulaResultType();
            return switch (cachedType) {
                case NUMERIC -> formatter.formatCellValue(cell).trim();
                case STRING -> cell.getStringCellValue().trim();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case ERROR -> "";
                default -> cell.getCellFormula();
            };
        } catch (Exception e) {
            log.warn("读取公式缓存值失败，回退到公式字符串。cell: {}", describe(cell), e);
        }

        // 第 3 选择：原始公式字符串
        try {
            return cell.getCellFormula();
        } catch (Exception e) {
            log.warn("读取公式字符串失败，返回空。cell: {}", describe(cell), e);
            return "";
        }
    }

    /**
     * 判断 cell 是否被划删除线（字体级 strikeout）
     * <p>
     * 业务里"删除线 = 软删除"约定，整行划线即整行 cell 字体 strikeout；按 cell 字体判定，
     * XSSF / HSSF 通用。富文本局部划线（同 cell 内仅部分文字划线）不在此判定范围，按需再扩展
     *
     * @return cell 字体带 strikeout 返回 true；空 cell / 无样式 / 异常一律 false
     */
    public static boolean isStrikethrough(Cell cell) {
        if (cell == null) {
            return false;
        }
        try {
            CellStyle style = cell.getCellStyle();
            if (style == null) {
                return false;
            }
            Workbook workbook = cell.getSheet().getWorkbook();
            Font font = workbook.getFontAt(style.getFontIndex());
            return font != null && font.getStrikeout();
        } catch (Exception e) {
            return false;
        }
    }

    private static String describe(Cell cell) {
        return cell.getSheet().getSheetName()
                + "!" + cell.getAddress().formatAsString();
    }
}
