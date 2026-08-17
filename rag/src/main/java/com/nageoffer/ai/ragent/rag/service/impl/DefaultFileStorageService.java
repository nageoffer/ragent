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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.lang.Assert;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.rag.util.DisplayType;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 后端无关的文件存储实现
 * <p>
 * 负责 namespace/key 组装、桶的语义归属（私有文档 → kbBucket，公共资产 → assetBucket）、Tika 类型探测
 * 与 {@link StoredFileDTO} 装配，底层裸操作委托给 {@link ObjectStorageClient}（S3 或 OSS，由
 * {@code rag.storage.type} 决定）；存储引用只保留裸 key，桶是部署级配置常量、不写进数据，改桶名不会令
 * 历史引用失联
 */
@Slf4j
@Service
public class DefaultFileStorageService implements FileStorageService {

    private static final Tika TIKA = new Tika();
    private static final String LOCK_KEY_PREFIX = "ragent:kb:space:init:";
    private static final long LOCK_WAIT_SECONDS = 30;

    private final ObjectStorageClient objectStorageClient;
    private final RedissonClient redissonClient;
    private final String kbBucket;
    private final String assetBucket;

    public DefaultFileStorageService(ObjectStorageClient objectStorageClient,
                                     RedissonClient redissonClient,
                                     RagStorageProperties properties) {
        this.objectStorageClient = objectStorageClient;
        this.redissonClient = redissonClient;
        this.kbBucket = properties.getKbBucket();
        this.assetBucket = properties.getAssetBucket();
    }

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String namespace, MultipartFile file) {
        validateNamespace(namespace);
        Assert.isFalse(file == null || file.isEmpty(), "上传文件不能为空");

        String originalFilename = file.getOriginalFilename();
        long size = file.getSize();

        // TIKA 只读流的前几 KB 来探测类型，不会加载整个文件
        String detectedContentType;
        try (InputStream is = file.getInputStream()) {
            detectedContentType = TIKA.detect(is, originalFilename);
        }

        // MultipartFile.getInputStream() 每次调用都返回新流（从底层临时文件重新打开），无需再创建临时文件
        try (InputStream is = file.getInputStream()) {
            String key = documentKey(namespace, originalFilename);
            objectStorageClient.streamPut(kbBucket, key, is, size, detectedContentType);
            return buildStoredFileDTO(key, originalFilename, detectedContentType, size);
        }
    }

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String namespace, InputStream content, long size, String originalFilename, String contentType) {
        validateNamespace(namespace);
        Assert.notNull(content, "上传内容不能为空");
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        String detected = resolveContentType(originalFilename, contentType);
        String key = documentKey(namespace, originalFilename);
        objectStorageClient.streamPut(kbBucket, key, content, size, detected);
        return buildStoredFileDTO(key, originalFilename, detected, size);
    }

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String namespace, byte[] content, String originalFilename, String contentType) {
        validateNamespace(namespace);
        Assert.notNull(content, "上传内容不能为空");
        String detected = resolveContentType(originalFilename, contentType);
        String key = documentKey(namespace, originalFilename);
        // byte[] 本身已在内存中，ByteArrayInputStream 不产生额外拷贝
        objectStorageClient.streamPut(kbBucket, key, new ByteArrayInputStream(content), content.length, detected);
        return buildStoredFileDTO(key, originalFilename, detected, content.length);
    }

    @Override
    @SneakyThrows
    public StoredFileDTO reliableUpload(String namespace, InputStream content, long size, String originalFilename, String contentType) {
        validateNamespace(namespace);
        Assert.notNull(content, "上传内容不能为空");
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        String detected = resolveContentType(originalFilename, contentType);
        String key = documentKey(namespace, originalFilename);
        objectStorageClient.reliablePut(kbBucket, key, content, size, detected);
        return buildStoredFileDTO(key, originalFilename, detected, size);
    }

    @Override
    @SneakyThrows
    public StoredFileDTO uploadAsset(byte[] content, String originalFilename, String contentType) {
        Assert.notNull(content, "上传内容不能为空");
        String detected = resolveContentType(originalFilename, contentType);
        String key = randomKey(originalFilename);
        objectStorageClient.streamPut(assetBucket, key, new ByteArrayInputStream(content), content.length, detected);
        return buildStoredFileDTO(key, originalFilename, detected, content.length);
    }

    @Override
    public InputStream openStream(String key) {
        Assert.notBlank(key, "对象 key 不能为空");
        return objectStorageClient.getObject(kbBucket, key);
    }

    @Override
    public void deleteByUrl(String key) {
        Assert.notBlank(key, "对象 key 不能为空");
        objectStorageClient.deleteObject(kbBucket, key);
    }

    @Override
    public String getPublicUrl(String key) {
        Assert.notBlank(key, "对象 key 不能为空");
        return objectStorageClient.buildPublicUrl(assetBucket, key);
    }

    @Override
    public void createKnowledgeSpace(String namespace) {
        validateNamespace(namespace);
        String markerKey = namespace + "/";
        if (objectStorageClient.objectExists(kbBucket, markerKey)) {
            return;
        }

        // 集群下用分布式锁 + 双重检查保证目录只建一次，替代旧 createBucket 的冲突保证
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + namespace);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("知识库目录初始化获取分布式锁被中断 namespace=" + namespace);
        }
        if (!locked) {
            throw new ServiceException("知识库目录初始化获取分布式锁超时 namespace=" + namespace);
        }

        try {
            if (objectStorageClient.objectExists(kbBucket, markerKey)) {
                return;
            }
            // 写一个 0 字节标记对象，使空知识库目录在控制台可见
            objectStorageClient.streamPut(kbBucket, markerKey, new ByteArrayInputStream(new byte[0]), 0, null);
            log.info("知识库目录创建成功 bucket={}, namespace={}", kbBucket, namespace);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void deleteKnowledgeSpace(String namespace) {
        validateNamespace(namespace);
        objectStorageClient.deleteByPrefix(kbBucket, namespace + "/");
    }

    /**
     * 组装知识库文档 key：{@code {namespace}/{uuid}.{ext}}
     */
    private String documentKey(String namespace, String originalFilename) {
        return namespace + "/" + randomKey(originalFilename);
    }

    private String extractSuffix(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return (idx < 0 || idx == filename.length() - 1) ? "" : filename.substring(idx + 1).trim();
    }

    /**
     * 生成随机对象 key：{@code {32位十六进制}.{ext}}，即去掉连字符的 UUID 加原始后缀
     */
    private String randomKey(String originalFilename) {
        String suffix = extractSuffix(originalFilename);
        String key = UUID.randomUUID().toString().replace("-", "");
        return suffix.isBlank() ? key : key + "." + suffix;
    }

    private void validateNamespace(String namespace) {
        Assert.notBlank(namespace, "namespace 不能为空");
    }

    private StoredFileDTO buildStoredFileDTO(String url, String originalFilename,
                                             String contentType, long size) {
        // 展示标签唯一产生点：扩展名优先，取值集合封闭
        String detectedType = DisplayType.of(originalFilename, contentType).getCode();
        return StoredFileDTO.builder()
                .url(url)
                .detectedType(detectedType)
                .mimeType(contentType)
                .size(size)
                .originalFilename(originalFilename)
                .build();
    }

    private String resolveContentType(String originalFilename, String contentType) {
        if (contentType != null && !contentType.isBlank()) return contentType;
        if (originalFilename != null && !originalFilename.isBlank()) return TIKA.detect(originalFilename);
        return null;
    }
}
