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

package com.nageoffer.ai.ragent.core.chunk;

import java.util.Map;

/**
 * 分块配置 sealed interface
 * 通过具体 record 实现类型安全的配置传递，消除魔法字符串
 *
 * @see FixedSizeOptions 固定大小切分配置
 * @see TextBoundaryOptions 文本边界切分配置（结构感知等）
 */
public sealed interface ChunkingOptions permits FixedSizeOptions, TextBoundaryOptions { // permits 允许实现类

    /**
     * 将配置导出为 Map，用于 API 返回和配置校验
     */
    Map<String, Integer> toConfigMap();
}
