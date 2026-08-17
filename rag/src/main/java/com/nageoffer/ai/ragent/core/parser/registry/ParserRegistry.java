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

package com.nageoffer.ai.ragent.core.parser.registry;

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypes;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 解析器注册表：(MIME × 解析档位) → 解析器，启动期建表，同一键被两个解析器认领即启动失败
 * <p>
 * 查找顺序四档，全不命中显式报错，不静默兜底到某个通用解析器：
 * <pre>
 *   精确 + 请求档位 → 通配 + 请求档位 → 精确 + FAST → 通配 + FAST → 抛错
 * </pre>
 * FAST 是全局兜底档，解析器只需在有专属实现的档位显式声明
 */
@Slf4j
@Component
public class ParserRegistry {

    private static final String WILDCARD_SUFFIX = "/*";

    /**
     * 启动自检清单：对外声明支持的扩展名，每个都必须被某个解析器精确认领，缺一个启动即失败
     * <p>
     * 清单落在扩展名而非 MIME 上，因为 MIME 是探测器的产出，拿它校验它自己恒为真；漏认领一个
     * 没听说过的 MIME（Tika 把 {@code .md} 探成 {@code text/x-web-markdown}）只会静默落到通配兜底
     */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx",
            "xls", "xlsx", "csv",
            "md", "markdown", "txt", "text",
            "html", "htm", "json", "xml", "rtf",
            "png", "jpg", "jpeg", "svg"
    );

    /**
     * 精确表：档位 → (全量 MIME → 解析器)
     */
    private final Map<ParseProfile, Map<String, DocumentParser>> exact = new EnumMap<>(ParseProfile.class);

    /**
     * 通配表：档位 → (MIME 大类前缀 → 解析器)，如 "text" → Tika
     */
    private final Map<ParseProfile, Map<String, DocumentParser>> wildcard = new EnumMap<>(ParseProfile.class);

    public ParserRegistry(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            Map<ParseProfile, Set<String>> claims = parser.supportedMimeTypes();
            if (claims == null || claims.isEmpty()) {
                throw new ServiceException("解析器未声明任何 (MIME × 档位) 认领：" + parser.getParserType());
            }
            claims.forEach((profile, mimeTypes) -> register(parser, profile, mimeTypes));
        }
    }

    private void register(DocumentParser parser, ParseProfile profile, Set<String> mimeTypes) {
        if (profile == null || mimeTypes == null || mimeTypes.isEmpty()) {
            throw new ServiceException("解析器认领清单存在空档位或空 MIME 集合：" + parser.getParserType());
        }
        for (String raw : mimeTypes) {
            if (!StringUtils.hasText(raw)) {
                throw new ServiceException("解析器认领了空 MIME：" + parser.getParserType());
            }
            String mime = raw.trim().toLowerCase(Locale.ROOT);
            boolean isWildcard = mime.endsWith(WILDCARD_SUFFIX);
            String key = isWildcard ? mime.substring(0, mime.length() - WILDCARD_SUFFIX.length()) : mime;
            Map<String, DocumentParser> table = (isWildcard ? wildcard : exact)
                    .computeIfAbsent(profile, k -> new HashMap<>());
            DocumentParser previous = table.put(key, parser);
            if (previous != null && previous != parser) {
                throw new ServiceException(String.format(
                        "解析器路由键冲突：档位=%s MIME=%s 同时被 %s 与 %s 认领，请显式区分",
                        profile.getCode(), raw, previous.getParserType(), parser.getParserType()));
            }
        }
    }

    @PostConstruct
    public void selfCheck() {
        MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();
        Tika tika = new Tika(mimeTypes);
        Map<String, DocumentParser> exactFallback = exact.getOrDefault(ParseProfile.FAST, Map.of());
        List<String> unclaimed = new ArrayList<>();
        for (String extension : new TreeSet<>(SUPPORTED_EXTENSIONS)) {
            String mime = tika.detect("probe." + extension);
            if (!exactFallback.containsKey(mime)) {
                unclaimed.add("." + extension + " → " + mime);
            }
        }
        if (!unclaimed.isEmpty()) {
            throw new ServiceException("解析器注册表自检失败，以下扩展名探测出的 MIME 无人精确认领，"
                    + "只会落到通配兜底：" + String.join(", ", unclaimed));
        }
        // 非兜底档只认精确 MIME：通配认领没法枚举成具体格式，档位差异会对外不可见
        List<String> illegalWildcards = new ArrayList<>();
        wildcard.forEach((profile, table) -> {
            if (profile != ParseProfile.FAST) {
                table.keySet().forEach(prefix -> illegalWildcards.add(profile.getCode() + " → " + prefix + WILDCARD_SUFFIX));
            }
        });
        if (!illegalWildcards.isEmpty()) {
            throw new ServiceException("非兜底档只允许精确 MIME 认领，以下通配认领无法枚举成具体格式："
                    + String.join(", ", illegalWildcards));
        }
        log.info("解析器注册表就绪 精确键={} 通配键={} 自检扩展名={} 全部精确命中",
                countKeys(exact), countKeys(wildcard), SUPPORTED_EXTENSIONS.size());
    }

    /**
     * 按 (MIME × 档位) 查找解析器
     *
     * @param mimeType 真实 MIME，允许带 {@code ;charset=} 参数
     * @param profile  请求档位，为空按默认档
     */
    public Optional<DocumentParser> find(String mimeType, ParseProfile profile) {
        String mime = normalize(mimeType);
        if (mime == null) {
            return Optional.empty();
        }
        ParseProfile requested = profile == null ? ParseProfile.defaultProfile() : profile;
        DocumentParser hit = lookup(mime, requested);
        if (hit == null && requested != ParseProfile.FAST) {
            hit = lookup(mime, ParseProfile.FAST);
        }
        return Optional.ofNullable(hit);
    }

    /**
     * 按 (MIME × 档位) 查找解析器，认不出来就报错，不塞给某个通用解析器产出垃圾文本
     */
    public DocumentParser require(String mimeType, ParseProfile profile) {
        return find(mimeType, profile).orElseThrow(() -> new ClientException(
                "未找到 MIME [" + mimeType + "] 在档位 ["
                        + (profile == null ? ParseProfile.defaultProfile() : profile).getCode() + "] 下对应的解析器"));
    }

    /**
     * 档位真正有区别的 MIME：某个非兜底档命中的解析器 ≠ 兜底档命中的解析器
     * <p>
     * 供上层决定"解析档位"这个选项该不该给用户看：两档命中同一解析器时档位是空操作，
     * 因此算出来而不写死一份格式清单
     */
    public Set<String> profileSensitiveMimeTypes() {
        Set<String> sensitive = new TreeSet<>();
        for (ParseProfile profile : ParseProfile.values()) {
            if (profile == ParseProfile.FAST) {
                continue;
            }
            exact.getOrDefault(profile, Map.of()).forEach((mime, parser) -> {
                if (lookup(mime, ParseProfile.FAST) != parser) {
                    sensitive.add(mime);
                }
            });
        }
        return sensitive;
    }

    /**
     * 该 MIME 是否有任何档位可解析，供上传前置拦截使用
     */
    public boolean canParse(String mimeType) {
        String mime = normalize(mimeType);
        if (mime == null) {
            return false;
        }
        for (ParseProfile profile : ParseProfile.values()) {
            if (lookup(mime, profile) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单档位查找：精确键优先于通配键
     */
    private DocumentParser lookup(String mime, ParseProfile profile) {
        DocumentParser hit = exact.getOrDefault(profile, Map.of()).get(mime);
        if (hit != null) {
            return hit;
        }
        int slash = mime.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return wildcard.getOrDefault(profile, Map.of()).get(mime.substring(0, slash));
    }

    private static String normalize(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static int countKeys(Map<ParseProfile, Map<String, DocumentParser>> table) {
        return table.values().stream().mapToInt(Map::size).sum();
    }
}
