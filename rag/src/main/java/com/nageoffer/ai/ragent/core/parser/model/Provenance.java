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

package com.nageoffer.ai.ragent.core.parser.model;

/**
 * Block 来源信息，落进块元数据供排障时定位原始文档位置
 * <p>
 * sheetName 只作溯源标记、不参与向量文本：sheet 名由 Excel 解析器另产一个 HeadingBlock 走章节路径
 *
 * @param sourceFile 原始文件标识，文件 ID 或文件名
 * @param sheetName  Excel sheet 名，非 Excel 来源为 null
 */
public record Provenance(String sourceFile, String sheetName) {

    public static Provenance ofFile(String sourceFile) {
        return new Provenance(sourceFile, null);
    }

    public static Provenance ofExcelCell(String sourceFile, String sheetName) {
        return new Provenance(sourceFile, sheetName);
    }
}
