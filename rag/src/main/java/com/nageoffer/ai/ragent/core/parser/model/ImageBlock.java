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
 * 图片 Block：由 ImageChunker 渲染成 {@code ![caption](url)} 的 atomic chunk，图片链接被切碎会导致前端渲染失败
 *
 * @param description VLM 图生文结果，同时用于 embedding 检索与喂 LLM 答题，MinerU 等不产图生文的来源为 null
 */
public record ImageBlock(
        Provenance provenance,
        AssetRef asset,
        String caption,
        String altText,
        String description
) implements Block {

    /**
     * 不产图生文的来源（MinerU / Excel 等）用此形态，description 置空
     */
    public ImageBlock(Provenance provenance, AssetRef asset, String caption, String altText) {
        this(provenance, asset, caption, altText, null);
    }
}
