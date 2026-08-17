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

package com.nageoffer.ai.ragent.core.parser.mineru;

/**
 * MinerU 申请上传链接的请求体(精简版,单文件)
 * <p>
 * 走 MinerU 官方"本地文件批量上传解析":只提交文件元信息,不带 url
 * 真实请求 JSON 由 {@link MinerUClient#requestUpload} 内部构造,字段名按 MinerU 官方:
 * <pre>
 * {
 *   "enable_formula": true,
 *   "enable_table":   true,
 *   "language":       "ch",
 *   "files": [
 *     {
 *       "name":     "xxx.pdf",
 *       "is_ocr":   false,
 *       "data_id":  "doc-uuid"
 *     }
 *   ]
 * }
 * </pre>
 *
 * @param fileName      文件名,必须带正确扩展名,MinerU 靠它识别格式
 * @param dataId        调用方业务标识,从 {@link MinerUStatus} 回看
 * @param isOcr         是否强制 OCR
 * @param enableTable   是否提取表格
 * @param enableFormula 是否提取公式
 * @param language      语言代码,遵循 MinerU(PaddleOCR)规范,如 ch / en / chinese_cht
 */
public record BatchSubmitRequest(
        String fileName,
        String dataId,
        boolean isOcr,
        boolean enableTable,
        boolean enableFormula,
        String language
) {
}
