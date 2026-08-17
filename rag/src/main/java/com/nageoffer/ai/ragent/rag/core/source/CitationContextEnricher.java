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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为已格式化的知识库上下文注入请求级引用编号
 * <p>
 * 上下文格式化阶段只写入内部 {@code data-ragent-doc-id}，来源装配完成后再依据
 * {@link SourceRef#getIndex()} 替换为模型可见的 {@code ref}。这样 Prompt、SSE、落库和前端
 * 始终复用同一份来源编号，同时不把内部文档 ID 暴露给模型
 * <p>
 * 引用开关关闭时按「无来源」处理：只抹掉内部锚点、不注入编号。无论开关如何，
 * 上下文都必须先过这一道，内部 docId 才不会漏进模型可见文本
 */
@Component
@RequiredArgsConstructor
public class CitationContextEnricher {

    private static final Pattern CONTENT_TAG = Pattern.compile(
            "(?m)^<content([^>]*) data-ragent-doc-id=\"([^\"]*)\">$");

    private final RAGConfigProperties ragConfigProperties;

    public String enrich(String kbContext, List<SourceRef> sources) {
        if (StrUtil.isBlank(kbContext)) {
            return StrUtil.emptyIfNull(kbContext);
        }

        Map<String, Integer> indexByDocId = Boolean.TRUE.equals(ragConfigProperties.getCitationEnabled())
                ? indexByDocId(sources)
                : Map.of();
        Matcher matcher = CONTENT_TAG.matcher(kbContext);
        StringBuilder result = new StringBuilder(kbContext.length());
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String docId = matcher.group(2);
            Integer index = indexByDocId.get(docId);
            String replacement = index == null
                    ? "<content" + attributes + ">"
                    : "<content" + attributes + " ref=\"" + index + "\">";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, Integer> indexByDocId(List<SourceRef> sources) {
        if (CollUtil.isEmpty(sources)) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (SourceRef source : sources) {
            if (source == null
                    || StrUtil.isBlank(source.getDocId())
                    || source.getIndex() == null) {
                continue;
            }
            result.putIfAbsent(source.getDocId(), source.getIndex());
        }
        return result;
    }
}
