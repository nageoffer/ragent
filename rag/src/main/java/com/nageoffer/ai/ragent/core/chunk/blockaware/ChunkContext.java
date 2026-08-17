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

package com.nageoffer.ai.ragent.core.chunk.blockaware;

import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;

import java.util.List;

/**
 * 切分上下文：调度器遍历 Block 列表时构造并传给每个 chunker，章节路径由 {@link HeadingHandler} 累积
 */
public record ChunkContext(List<String> outlinePath, ChunkBudget budget) {

    public ChunkContext {
        outlinePath = outlinePath == null ? List.of() : List.copyOf(outlinePath);
    }

    public static ChunkContext of(List<String> outlinePath, ChunkBudget budget) {
        return new ChunkContext(outlinePath, budget);
    }
}
