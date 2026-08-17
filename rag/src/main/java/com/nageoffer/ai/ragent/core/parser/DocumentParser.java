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

import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;

import java.util.Map;
import java.util.Set;

/**
 * 文档解析器统一接口：核心是 {@link #parseStructured}，产出含 Block 列表的 {@link ParsedDocument}
 * <p>
 * 解析器通过 {@link #supportedMimeTypes()} 显式认领 (MIME × 档位)，由 {@code ParserRegistry}
 * 在启动期建表，键冲突即启动失败
 */
public interface DocumentParser {

    /**
     * 解析器类型标识，取值见 {@link ParserType}
     */
    String getParserType();

    /**
     * 结构化解析：产出有序的 Block 列表（章节、段落、表格、图片等），mimeType 与 options 可为空
     */
    ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options);

    /**
     * 认领清单：档位 → 该档位下认领的 MIME 集合，不得为空
     * <p>
     * MIME 一律小写；支持 {@code type/*} 通配，精确键优先于通配键；未在请求档位注册时回落到全局
     * 兜底档 {@link ParseProfile#FAST}，故只在该档位有专属解析器时才需声明，如 Excel 的 FAST 档
     * 走 POI 快速 key-val、FIDELITY 档才交给 MinerU 做版面解析
     */
    Map<ParseProfile, Set<String>> supportedMimeTypes();
}
