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
 * 段落 Block：由 ParagraphChunker 按 token 切分，可跨段落合并到目标长度，但不跨 heading
 *
 * @param text 段落文本，保留链接、图片与行内代码标记而丢掉强调标记；markdown 里内嵌的非表格 HTML
 *             也原样落在此处（表格另走 {@link HtmlTableBlock}），故不能假定它是纯文本
 */
public record ParagraphBlock(
        Provenance provenance,
        String text
) implements Block {
}
