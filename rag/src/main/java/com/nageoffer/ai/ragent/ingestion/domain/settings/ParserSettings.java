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

package com.nageoffer.ai.ragent.ingestion.domain.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 解析器设置实体类
 * 定义文档解析节点的配置参数，包含多个解析规则
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParserSettings {

    /**
     * 解析规则列表
     * 根据不同MIME类型匹配不同的解析器
     */
    private List<ParserRule> rules;

    /**
     * 解析规则配置
     * 定义单个解析规则，指定哪些MIME类型应该使用哪种解析器
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ParserRule {

        /**
         * 文档类型
         * 如 PDF、WORD、MARKDOWN 等
         */
        private String mimeType;

        /**
         * 解析器的额外配置选项
         */
        private Map<String, Object> options;
    }
}
