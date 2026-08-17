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

package com.nageoffer.ai.ragent.rag.core.source;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 回答行内引用标记工具
 * <p>
 * 引用随回答正文持久化以保持位置稳定，但进入下一轮模型历史或推荐问题生成时应移除，
 * 避免上一轮的局部编号污染本轮引用编号
 */
public final class CitationMarkup {

    /**
     * 匹配系统定义的行内引用格式：
     * [1](#cite-1)、[10](#cite-10)
     * <p>
     * 不强制显示编号与锚点编号一致，因为清理逻辑需要兼容模型偶尔产生的错误格式，
     * 例如 [1](#cite-2) 也应从下一轮上下文中移除。
     */
    private static final Pattern INLINE_CITATION =
            Pattern.compile("\\[[1-9]\\d*]\\(#cite-[1-9]\\d*\\)");

    private CitationMarkup() {
    }

    public static String strip(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        return INLINE_CITATION.matcher(content).replaceAll("");
    }
}
