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
 * 原始 HTML 表格 Block：MinerU 的版面解析产出 {@code <table>} 而非管道表，由 HtmlTableChunker 按行切分
 * <p>
 * 刻意不拆成 {@link TableBlock} 的 headers/rows：合并单元格、单元格内的换行与公式片段在展开成二维表时
 * 必然失真，保留原 HTML 让展示端自行渲染
 *
 * @param html 完整表格 HTML，以 {@code <table} 开头
 */
public record HtmlTableBlock(
        Provenance provenance,
        String html
) implements Block {
}
