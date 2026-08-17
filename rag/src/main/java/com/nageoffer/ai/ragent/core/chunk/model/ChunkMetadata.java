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

package com.nageoffer.ai.ragent.core.chunk.model;

import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import lombok.Builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 块的结构化元数据
 * <p>
 * {@link #toMap()} 是全系统唯一的元数据序列化点，关系库、向量库、关键词索引、图库全部调它，新增字段只改这一处；
 * 键名统一 snake_case，与向量库 metadata 及 ES 索引字段一致，文档级键（{@code doc_id} / {@code chunk_index}）
 * 不是块的内在属性，由各 sink 自己补
 *
 * @param outlinePath 章节层级路径，Excel 的 sheet 名亦走这里
 * @param provenance  原始来源（文件、sheet）
 * @param extras      开放扩展位：块级加工产出（摘要、关键词）与调用方注入的文档级元数据
 */
@Builder
public record ChunkMetadata(
        List<String> outlinePath,
        List<AssetRef> assets,
        Provenance provenance,
        Map<String, Object> extras
) {

    public static final String KEY_ASSETS = "assets";
    public static final String KEY_SOURCE_FILE = "source_file";
    public static final String KEY_SHEET_NAME = "sheet_name";

    public ChunkMetadata {
        outlinePath = immutableCopy(outlinePath);
        assets = immutableCopy(assets);
        extras = extras == null || extras.isEmpty() ? Map.of() : Map.copyOf(extras);
    }

    /**
     * 空元数据：仅用于测试与确实没有任何结构信息的场景
     */
    public static ChunkMetadata empty() {
        return new ChunkMetadata(List.of(), List.of(), null, Map.of());
    }

    /**
     * 合并扩展位并返回新对象：块级加工（摘要 / 关键词）与文档级元数据注入用
     */
    public ChunkMetadata withExtras(Map<String, Object> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(extras);
        merged.putAll(additional);
        return new ChunkMetadata(outlinePath, assets, provenance, merged);
    }

    /**
     * 序列化为各索引后端通用的扁平 Map：空值一律不写入，向量库 JSONB 与 ES 文档都按需读键
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        // 扩展位先写，结构化键后写：结构化事实是权威，加工产物不得覆盖它
        map.putAll(extras);
        if (!assets.isEmpty()) {
            List<Map<String, Object>> assetMaps = new ArrayList<>(assets.size());
            for (AssetRef asset : assets) {
                Map<String, Object> one = new LinkedHashMap<>();
                putIfPresent(one, "url", asset.publicUrl());
                putIfPresent(one, "mime", asset.mime());
                assetMaps.add(one);
            }
            map.put(KEY_ASSETS, assetMaps);
        }
        if (provenance != null) {
            putIfPresent(map, KEY_SOURCE_FILE, provenance.sourceFile());
            putIfPresent(map, KEY_SHEET_NAME, provenance.sheetName());
        }
        return map;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return source == null || source.isEmpty() ? List.of() : List.copyOf(source);
    }
}
