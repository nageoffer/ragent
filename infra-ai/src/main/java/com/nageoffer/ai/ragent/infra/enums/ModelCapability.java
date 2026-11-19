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

package com.nageoffer.ai.ragent.infra.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 模型能力枚举类
 * 定义了AI模型支持的各种能力类型
 */
@Getter
@RequiredArgsConstructor
public enum ModelCapability {

    /**
     * 聊天对话能力
     * 支持与用户进行自然语言对话交互
     */
    CHAT("Chat"),

    /**
     * 向量嵌入能力
     * 将文本转换为向量表示，用于语义搜索和相似度计算
     */
    EMBEDDING("Embedding"),

    /**
     * 重排序能力
     * 对搜索结果进行重新排序，提高相关性
     */
    RERANK("Rerank"),

    /**
     * 结构化判断能力
     * 对给定内容按类型化问题作答，返回结构化结论而不是正文
     */
    JEV("Jev");

    /**
     * 能力的显示名称
     */
    private final String displayName;
}
