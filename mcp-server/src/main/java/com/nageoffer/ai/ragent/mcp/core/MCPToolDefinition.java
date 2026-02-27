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

package com.nageoffer.ai.ragent.mcp.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolDefinition {

    /**
     * 工具的唯一标识符
     */
    private String toolId;

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具的详细描述
     */
    private String description;

    /**
     * 工具使用示例列表
     */
    private List<String> examples;

    /**
     * 工具参数定义映射，key为参数名，value为参数定义
     */
    private Map<String, ParameterDef> parameters;

    /**
     * 是否需要用户ID，默认为true
     */
    @Builder.Default
    private boolean requireUserId = true;

    /**
     * 参数定义类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterDef {

        /**
         * 参数描述
         */
        private String description;

        /**
         * 参数类型，默认为"string"
         */
        @Builder.Default
        private String type = "string";

        /**
         * 是否必填，默认为false
         */
        @Builder.Default
        private boolean required = false;

        /**
         * 参数默认值
         */
        private Object defaultValue;

        /**
         * 枚举值列表
         */
        private List<String> enumValues;
    }
}
