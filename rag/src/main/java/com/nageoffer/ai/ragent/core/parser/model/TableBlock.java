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

import java.util.List;

/**
 * 表格 Block：由 TableChunker 按 rowsPerChunk 切分，每个 chunk 都重复带上 headers
 * <p>
 * 到这里合并单元格已被 ExcelTableNormalizer 展开填充，多行表头已展平为单行、列名以竖线拼接如 "财务|收入"
 */
public record TableBlock(
        Provenance provenance,
        List<String> headers,
        List<List<String>> rows
) implements Block {
}
