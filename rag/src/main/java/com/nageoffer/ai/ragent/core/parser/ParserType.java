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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档解析器类型枚举
 */
@Getter
@RequiredArgsConstructor
public enum ParserType {

    /**
     * Tika 解析器（用于 Text 等基础格式）
     */
    TIKA("Tika"),

    /**
     * Markdown 解析器
     */
    MARKDOWN("Markdown"),

    /**
     * Apache POI Excel 解析器（合并单元格 / 多行表头 / 超链接）
     */
    EXCEL_POI("ExcelPoi"),

    /**
     * CSV 解析器（自动探测字符集 + RFC4180，产单张 key-val 表格）
     */
    CSV("Csv"),

    /**
     * MinerU SaaS 解析器（PDF / Word / PPT / Excel，含表格、图片、版面）
     */
    MINERU("MinerU"),

    /**
     * 图片解析器（PNG / JPG，VLM 图生文 + 原图入库）
     */
    IMAGE("Image");

    /**
     * 解析器类型名称
     */
    private final String type;
}
