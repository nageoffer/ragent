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
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LocalFileStorageServiceImpl implements FileStorageService {

    private final S3Client s3Client;

    private static final Tika TIKA = new Tika();

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, MultipartFile file) {
        Assert.notBlank(bucketName, "bucketName 不能为空");
        Assert.isFalse(file == null || file.isEmpty(), "上传文件不能为空");

        String originalFilename = file.getOriginalFilename();
        String suffix = extractSuffix(originalFilename);

        String s3Key = UUID.randomUUID().toString().replace("-", "")
                + (suffix.isBlank() ? "" : "." + suffix);

        long size = file.getSize();

        // 更稳的类型检测方式：可以基于文件名/流
        String detected;
        try (InputStream is = file.getInputStream()) {
            // Tika 支持 detect(InputStream, String)
            detected = TIKA.detect(is, originalFilename);
        }

        // 上传：使用 InputStream，避免一次性读入内存
        try (InputStream uploadIs = file.getInputStream()) {
            s3Client.putObject(
                    b -> b.bucket(bucketName)
                            .key(s3Key)
                            .contentType(detected)
                            .build(),
                    RequestBody.fromInputStream(uploadIs, size)
            );
        }

        String url = toS3Url(bucketName, s3Key);

        return StoredFileDTO.builder()
                .url(url)
                .detectedType(normalizeType(detected, suffix))
                .size(size)
                .originalFilename(originalFilename)
                .build();
    }

    @Override
    public InputStream openStream(String url) {
        S3Location loc = parseS3Url(url);
        return s3Client.getObject(b -> b.bucket(loc.bucket()).key(loc.key()));
    }

    @Override
    @SneakyThrows
    public void deleteByUrl(String url) {
        FileSystemUtils.deleteRecursively(Path.of(url));
    }

    private String toS3Url(String bucket, String key) {
        return "s3://" + bucket + "/" + key;
    }

    private S3Location parseS3Url(String url) {
        try {
            URI uri = URI.create(url);
            if (!"s3".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Unsupported url scheme: " + url);
            }

            String bucket = uri.getHost();
            String path = uri.getPath(); // /key...
            if (bucket == null || bucket.isBlank()) {
                throw new IllegalArgumentException("Invalid s3 url(bucket missing): " + url);
            }

            String key = (path != null && path.startsWith("/")) ? path.substring(1) : path;
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Invalid s3 url(key missing): " + url);
            }

            return new S3Location(bucket, key);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid s3 url: " + url, e);
        }
    }

    private record S3Location(String bucket, String key) {
    }

    private String extractSuffix(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).trim();
    }

    /**
     * 你已有 normalizeType，这里保留签名，按你项目实现替换
     */
    private String normalizeType(String tikaType, String suffix) {
        if (tikaType == null) {
            return suffix == null ? "" : suffix;
        }
        return tikaType;
    }
}
