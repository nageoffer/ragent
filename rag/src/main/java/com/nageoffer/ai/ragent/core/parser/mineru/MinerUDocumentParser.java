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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MinerU 文档解析器（PDF / Word / PPT / Excel）：走官方「本地文件批量上传解析」，上传后轮询等结果
 * <p>
 * 本地上传链路不依赖任何公网可达的源文件 URL，适配内网部署；解析许可由 {@link RPermitExpirableSemaphore}
 * 跨实例发放，压住 MinerU 侧同时在跑的任务数，配置项见 {@link MinerUProperties}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinerUDocumentParser implements DocumentParser {

    /**
     * 版面解析类：PDF / Word / PPT 只有 MinerU 一条路，两个档位都由它承担
     */
    private static final Set<String> LAYOUT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/x-pdf",
            "application/msword",
            "application/vnd.ms-word",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
    );

    /**
     * 表格类只在保真档认领：默认走 POI 快速 key-val，用户选保真档才付 MinerU 的成本
     */
    private static final Set<String> SPREADSHEET_MIME_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel"
    );

    /**
     * options 字段：文件名，写入 Provenance.sourceFile
     */
    public static final String OPT_SOURCE_FILE = "sourceFile";

    /**
     * options 字段：文档 ID，用于资产 key 命名，不传时自动生成 UUID
     */
    public static final String OPT_DOCUMENT_ID = "documentId";

    /**
     * ParsedDocument.metadata 字段：MinerU 分配的 batchId，排障时凭它去 MinerU 侧查任务
     */
    public static final String META_BATCH_ID = "minerU.batchId";

    /**
     * ParsedDocument.metadata 字段：zip 下载 URL
     */
    public static final String META_ZIP_URL = "minerU.zipUrl";

    private final MinerUClient minerUClient;
    private final MinerUPollingExecutor pollingExecutor;
    private final MinerUResultUnpacker resultUnpacker;
    private final MinerUProperties properties;
    private final RedissonClient redissonClient;

    @PostConstruct
    void initSemaphore() {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(properties.getSemaphoreName());
        semaphore.setPermits(properties.getConcurrencyLimit());
        log.info("MinerU 分布式解析限流初始化: semaphoreName={}, maxConcurrent={}",
                properties.getSemaphoreName(), properties.getConcurrencyLimit());
    }

    @Override
    public String getParserType() {
        return ParserType.MINERU.getType();
    }

    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(
                ParseProfile.FAST, LAYOUT_MIME_TYPES,
                ParseProfile.FIDELITY, SPREADSHEET_MIME_TYPES
        );
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            throw new ServiceException("MinerU 解析输入字节为空");
        }

        String permitId = null;
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(properties.getSemaphoreName());
        try {
            permitId = semaphore.tryAcquire(
                    properties.getMaxWaitSeconds(),
                    properties.getLeaseSeconds(),
                    TimeUnit.SECONDS
            );
            if (permitId == null) {
                throw new ServiceException("MinerU 解析任务过多，请稍后重试");
            }
            return doParseStructured(content, mimeType, options);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 获取解析许可被中断");
        } finally {
            if (permitId != null) {
                boolean released = semaphore.tryRelease(permitId);
                if (!released) {
                    log.warn("MinerU parse permit already expired or released, permitId={}", permitId);
                }
            }
        }
    }

    private ParsedDocument doParseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        String sourceFile = extractString(options, OPT_SOURCE_FILE, "");
        String documentId = extractString(options, OPT_DOCUMENT_ID, UUID.randomUUID().toString());
        String uploadName = resolveUploadName(sourceFile, mimeType, documentId);

        // 1. 申请上传链接，只提交元信息、不带 url
        BatchSubmitRequest request = buildSubmitRequest(uploadName, documentId);
        BatchUploadTicket ticket = minerUClient.requestUpload(request);

        // 2. 把源文件字节直接 PUT 上传到 MinerU OSS
        minerUClient.uploadFile(ticket.uploadUrl(), content);
        log.info("MinerU 源文件上传完毕 documentId={} batchId={}", documentId, ticket.batchId());

        // 3. 阻塞等待完成，上传动作本身就触发 MinerU 提交解析，无需再调提交接口
        MinerUStatus status;
        try {
            status = pollingExecutor
                    .submitAndAwait(ticket.batchId(), Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .get(properties.getTimeoutSeconds() + 30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ServiceException("MinerU 等待超时(包含调度缓冲)batchId=" + ticket.batchId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 等待被中断 batchId=" + ticket.batchId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ServiceException("MinerU 等待异常 batchId=" + ticket.batchId() + ": " + cause.getMessage());
        }

        // 4. 下载 zip
        byte[] zipBytes = minerUClient.downloadZip(status.zipUrl());

        // 5. 解包为 ParsedDocument
        ParsedDocument parsed = resultUnpacker.unpack(zipBytes, sourceFile, documentId);

        // 6. batchId + zipUrl 注入 metadata，供下游节点持久化后排障
        Map<String, Object> mergedMeta = new HashMap<>(parsed.metadata() == null ? Map.of() : parsed.metadata());
        mergedMeta.put(META_BATCH_ID, ticket.batchId());
        mergedMeta.put(META_ZIP_URL, status.zipUrl());
        mergedMeta.put("parser", getParserType());
        mergedMeta.put("mimeType", mimeType == null ? "" : mimeType);

        return ParsedDocument.of(parsed.blocks(), mergedMeta);
    }

    /**
     * 计算上传到 MinerU 的文件名，必须带扩展名，MinerU 靠它识别格式；缺原始文件名时按 mimeType 补全
     */
    private String resolveUploadName(String sourceFile, String mimeType, String documentId) {
        if (sourceFile != null && !sourceFile.isBlank()) {
            return sourceFile;
        }
        return "doc-" + documentId + extFromMime(mimeType);
    }

    private BatchSubmitRequest buildSubmitRequest(String fileName, String documentId) {
        return new BatchSubmitRequest(
                fileName,
                documentId,
                properties.isOcr(),
                properties.isEnableTable(),
                properties.isEnableFormula(),
                properties.getLanguage()
        );
    }

    private static String extFromMime(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.contains("pdf")) return ".pdf";
        if (lower.contains("wordprocessingml")) return ".docx";
        if (lower.contains("msword")) return ".doc";
        if (lower.contains("presentationml")) return ".pptx";
        if (lower.contains("powerpoint")) return ".ppt";
        if (lower.contains("spreadsheetml")) return ".xlsx";
        if (lower.contains("ms-excel") || lower.contains("excel")) return ".xls";
        return ".bin";
    }

    private static String extractString(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? defaultValue : v.toString();
    }
}
