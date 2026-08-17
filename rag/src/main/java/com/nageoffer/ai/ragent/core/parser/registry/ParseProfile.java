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

package com.nageoffer.ai.ragent.core.parser.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 解析档位：愿意付多少成本换多少保真度，与格式（由 MIME 唯一确定）是两个正交维度
 * <p>
 * 枚举名是引擎侧的词，不是给用户看的词：界面上这两档叫"规整表格 / 复杂表格"，那份文案由
 * {@code IngestionSpecSchemaProvider} 单点下发
 */
public enum ParseProfile {

    /**
     * 快速档：本地解析器优先，零外部成本；也是全局兜底档，请求档位无匹配时回落到它，仍无则显式报错
     */
    FAST("fast"),

    /**
     * 保真档：付出外部服务成本换版面还原度，如 Excel 走 MinerU 版面解析
     */
    FIDELITY("fidelity");

    private final String code;

    ParseProfile(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public static ParseProfile defaultProfile() {
        return FAST;
    }

    /**
     * 宽松解析：空值回落默认档，无法识别的取值直接报错而非静默兜底
     */
    @JsonCreator
    public static ParseProfile from(String code) {
        if (code == null || code.isBlank()) {
            return defaultProfile();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (ParseProfile profile : values()) {
            if (profile.code.equals(normalized)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("未知解析档位：" + code);
    }
}
