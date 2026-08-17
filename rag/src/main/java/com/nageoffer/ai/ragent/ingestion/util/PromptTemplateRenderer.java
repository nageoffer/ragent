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

package com.nageoffer.ai.ragent.ingestion.util;

import java.util.Map;

/**
 * 提示词模板渲染器
 */
public final class PromptTemplateRenderer {

    private PromptTemplateRenderer() {
    }

    public static String render(String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) {
            return template;
        }
        String out = template;
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                String key = "{{" + entry.getKey() + "}}";
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                out = out.replace(key, value);
            }
        }
        return out;
    }
}
