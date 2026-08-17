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
import java.util.Map;

/**
 * 解析器统一输出：有序 Block 列表 + 文档级元数据
 * <p>
 * 由 DocumentParser.parseStructured() 返回，作为解析阶段 → ChunkerNode 阶段之间的契约
 *
 * @param blocks   有序 Block 列表（章节、段落、表格、图片等按文档原始顺序）
 * @param metadata 文档级元数据，如来源、页数、解析器、耗时等
 */
public record ParsedDocument(List<Block> blocks, Map<String, Object> metadata) {

    public static ParsedDocument of(List<Block> blocks) {
        return new ParsedDocument(blocks != null ? blocks : List.of(), Map.of());
    }

    public static ParsedDocument of(List<Block> blocks, Map<String, Object> metadata) {
        return new ParsedDocument(blocks != null ? blocks : List.of(), metadata != null ? metadata : Map.of());
    }
}
