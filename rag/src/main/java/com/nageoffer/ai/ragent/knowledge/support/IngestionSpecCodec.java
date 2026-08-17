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

package com.nageoffer.ai.ragent.knowledge.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 文档级摄取配置的读写、校验与归一化：单点
 * <p>
 * 取值范围由 {@link ChunkBudget} 与 {@link ParseProfile} 的构造期保证，此处只把构造异常翻译成
 * 用户可读的报错；缺失字段一律回落 {@link IngestionSpec#defaults()}，全系统只此一份默认值；
 * 前端提交什么形状都在此收敛成规整 JSON 落库，读路径不必再探测
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionSpecCodec {

    /**
     * 预算字段在线路上的键名：本类按它读入，{@link IngestionSpecSchemaProvider} 按它下发表单，
     * 必须共用这一份常量——两边各写一份字符串的话，键名一改就是提交了却静默走默认值，
     * 前端照常提交、后端照常入库，没有任何一步会报错
     */
    public static final String KEY_MAX_CHARS = "maxChars";
    public static final String KEY_OVERLAP_CHARS = "overlapChars";
    public static final String KEY_ROWS_PER_CHUNK = "rowsPerChunk";
    public static final String KEY_TOLERANCE_FACTOR = "toleranceFactor";

    private static final String KEY_PARSE_PROFILE = "parseProfile";

    /**
     * 不分块哨兵：沿用前端既有约定的 {@code -1}，线路上（提交、落库、出参、schema 下发）只有这一个表示
     * <p>
     * 领域对象内部用的是 {@link ChunkBudget} 的 {@code Integer.MAX_VALUE}，两者的翻译只发生在本类
     */
    public static final int WHOLE_DOCUMENT_SENTINEL = -1;

    private final ObjectMapper objectMapper;

    /**
     * 读：库里的 JSON → 配置对象，空值或损坏一律回落默认
     * <p>
     * 必须走 {@link SpecWire} 而不是直接 readValue 成领域对象：库里的 {@code maxChars} 是 {@code -1}，
     * 撞上 {@link ChunkBudget} 的"必须 &gt; 0"校验会被下面的 catch 悄悄换成默认配置，
     * 整篇不分块的文档于是无声地按 1024 切开
     */
    public IngestionSpec read(String json) {
        if (!StringUtils.hasText(json)) {
            return IngestionSpec.defaults();
        }
        try {
            return objectMapper.readValue(json, SpecWire.class).toDomain();
        } catch (Exception e) {
            log.warn("摄取配置解析失败，回落默认配置：{}", json, e);
            return IngestionSpec.defaults();
        }
    }

    /**
     * 写：配置对象 → 落库 JSON
     */
    public String write(IngestionSpec spec) {
        try {
            return objectMapper.writeValueAsString(SpecWire.of(spec == null ? IngestionSpec.defaults() : spec));
        } catch (Exception e) {
            throw new ClientException("摄取配置序列化失败");
        }
    }

    /**
     * 校验并归一化前端提交的配置 JSON，返回落库用的规整 JSON
     *
     * @param rawJson 前端提交的原始 JSON，可空（空表示全默认）
     * @return 规整后的 JSON，入参为空时返回 null（列留空即表示走默认）
     */
    public String normalize(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(rawJson.trim(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ClientException("摄取配置 JSON 格式不合法");
        }
        try {
            return write(fromMap(raw));
        } catch (IllegalArgumentException e) {
            throw new ClientException("摄取配置不合法：" + e.getMessage());
        }
    }

    private IngestionSpec fromMap(Map<String, Object> raw) {
        return IngestionSpec.of(ParseProfile.from(readString(raw, KEY_PARSE_PROFILE)),
                toBudget(readInt(raw, KEY_MAX_CHARS), readInt(raw, KEY_OVERLAP_CHARS),
                        readInt(raw, KEY_ROWS_PER_CHUNK), readInt(raw, KEY_TOLERANCE_FACTOR)));
    }

    /**
     * 四个整数 → 分块预算：哨兵翻译与缺失回落只有这一份
     * <p>
     * 前端提交的扁平键与库里回读的嵌套 {@code budget} 都收在这里；旧数据里的 {@code Integer.MAX_VALUE}
     * 同样落回整篇不分块，它正是 {@link ChunkBudget#wholeDocument()} 的内部取值
     */
    private static ChunkBudget toBudget(Integer maxChars, Integer overlap, Integer rows, Integer tolerance) {
        if (maxChars != null && maxChars == WHOLE_DOCUMENT_SENTINEL) {
            return ChunkBudget.wholeDocument();
        }
        ChunkBudget defaults = ChunkBudget.defaults();
        int budget = maxChars != null && maxChars > 0 ? maxChars : defaults.maxChars();
        return new ChunkBudget(
                budget,
                // 缺省重叠按块大小等比给：默认预算里那个数是配 1024 的，照搬到小块上会被压到 budget-1，切一片只前进一个字
                overlap != null && overlap >= 0 ? overlap : ChunkBudget.defaultOverlapFor(budget),
                rows != null && rows > 0 ? rows : defaults.rowsPerChunk(),
                tolerance != null && tolerance > 0 ? tolerance : defaults.toleranceFactor());
    }

    private static Integer readInt(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String readString(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 摄取配置的线路形状：与 {@link IngestionSpec} 同构，唯一区别是整篇不分块用
     * {@link #WHOLE_DOCUMENT_SENTINEL} 表达，而非领域内部的 {@code Integer.MAX_VALUE}
     * <p>
     * 线路上进出必须是同一个值：直接序列化领域对象的话，前端提交 {@code -1}、库里躺着
     * {@code 2147483647}、出参再原样送回，重新打开编辑弹窗就会在"块大小"里看见一个天文数字
     *
     * @param version      结构版本，缺失按当前版本
     * @param parseProfile 解析档位
     * @param budget       分块预算
     */
    private record SpecWire(int version, ParseProfile parseProfile, BudgetWire budget) {

        static SpecWire of(IngestionSpec spec) {
            ChunkBudget budget = spec.budget();
            return new SpecWire(spec.version(), spec.parseProfile(), budget.isWholeDocument()
                    ? new BudgetWire(WHOLE_DOCUMENT_SENTINEL, 0, WHOLE_DOCUMENT_SENTINEL, budget.toleranceFactor())
                    : new BudgetWire(budget.maxChars(), budget.overlapChars(),
                    budget.rowsPerChunk(), budget.toleranceFactor()));
        }

        IngestionSpec toDomain() {
            return new IngestionSpec(
                    version > 0 ? version : IngestionSpec.CURRENT_VERSION,
                    parseProfile,
                    budget == null
                            ? ChunkBudget.defaults()
                            : toBudget(budget.maxChars(), budget.overlapChars(),
                            budget.rowsPerChunk(), budget.toleranceFactor()));
        }
    }

    /**
     * 分块预算的线路形状：字段允许缺失，缺失一律由 {@link #toBudget} 回落默认
     */
    private record BudgetWire(Integer maxChars, Integer overlapChars, Integer rowsPerChunk, Integer toleranceFactor) {
    }
}
