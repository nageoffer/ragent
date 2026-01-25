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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 文档切分策略工厂，用于管理并获取不同的文档切分实现
 * 通过构造器注入所有 {@link ChunkingStrategy} 类型的 Bean，在初始化时自动注册
 */
@Component
@RequiredArgsConstructor
public class ChunkingStrategyFactory {

    private final List<ChunkingStrategy> chunkingStrategies;
    private volatile Map<ChunkingMode, ChunkingStrategy> strategies = Map.of();

    /**
     * 根据策略枚举获取对应的切分策略实现
     *
     * @param type 切分策略类型
     * @return {@link ChunkingStrategy} 切分策略实现类
     * @throws IllegalArgumentException 如果指定的策略类型不存在
     */
    public Optional<ChunkingStrategy> findStrategy(ChunkingMode type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(strategies.get(type));
    }

    /**
     * 获取指定类型的切分策略，如果不存在则抛出异常
     *
     * @param type 切分策略类型
     * @return {@link ChunkingStrategy} 切分策略实现类
     * @throws IllegalArgumentException 如果指定的策略类型不存在
     */
    public ChunkingStrategy requireStrategy(ChunkingMode type) {
        Objects.requireNonNull(type, "ChunkingMode type must not be null");
        return findStrategy(type)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + type));
    }

    @PostConstruct
    public void init() {
        Map<ChunkingMode, ChunkingStrategy> map = new EnumMap<>(ChunkingMode.class);

        chunkingStrategies.forEach(s -> {
            ChunkingStrategy old = map.put(s.getType(), s);
            if (old != null) {
                throw new IllegalStateException(
                        "Duplicate ChunkingStrategy for type: " + s.getType()
                                + " (" + old.getClass().getName() + " vs " + s.getClass().getName() + ")"
                );
            }
        });

        this.strategies = Map.copyOf(map);
    }
}
