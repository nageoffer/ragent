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

package com.nageoffer.ai.ragent.core.parser.image;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片解析配置（图生文）
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.image-parse")
public class ImageParseProperties {

    /**
     * 图生文引导提示词:要求 VLM 输出中文描述 + 图中文字 OCR
     */
    private String descriptionPrompt = "请用中文详细描述这张图片的内容；若图中包含文字，请逐字识别并完整列出（OCR）。"
            + "先给出整体内容描述，再用\"图中文字：\"另起一段列出识别到的所有文字。";

    /**
     * 描述输出 token 上限,控成本与 embedding 体量;<=0 表示不限制
     */
    private Integer maxOutputTokens = 1024;

    /**
     * 是否给文档内嵌图也做图生文
     * <p>
     * 关掉则内嵌图的向量文本回落成一条图片 URL，等于永远召回不到;开着的代价是每张图一次 VLM 调用
     */
    private boolean embeddedDescribeEnabled = true;
}
