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

package com.nageoffer.ai.ragent.knowledge.handler;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 远程文件拉取服务
 * 封装远程文件的 HEAD 预检、流式下载、变更检测等逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteFileFetcher {

    private final HttpClientHelper httpClientHelper;
    private final FileStorageService fileStorageService;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    /**
     * 流式拉取远程文件并上传到存储（用于文档上传场景）
     */
    public StoredFileDTO fetchAndStore(String bucketName, String url) {
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);
        Long headContentLength = headResponse == null ? null : headResponse.contentLength();
        checkSizeLimit(maxBytes, headContentLength);

        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            String fileName = firstHasText(response.fileName(), headResponse == null ? null : headResponse.fileName(), "remote-file");
            String contentType = firstHasText(response.contentType(), headResponse == null ? null : headResponse.contentType(), null);
            // 部分源站的 Content-Length/HEAD 响应并不可靠，固定长度上传会在字节数不一致时失败
            // 远程导入统一先落临时文件，以实际读取到的字节数作为上传大小
            return uploadViaTemp(bucketName, response.bodyStream(), fileName, contentType, maxBytes);
        }
    }

    /**
     * 流式拉取远程文件并检测变更（用于定时刷新场景）
     * 返回的 RemoteFetchResult 实现了 AutoCloseable，调用方必须用 try-with-resources 管理生命周期
     */
    public RemoteFetchResult fetchIfChanged(String url, String lastEtag, String lastModified,
                                            String lastContentHash, String fallbackFileName) {
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);

        if (headResponse != null) {
            checkSizeLimit(maxBytes, headResponse.contentLength());
            String etag = trimOrNull(headResponse.etag());
            String headLastModified = trimOrNull(headResponse.lastModified());
            String previousEtag = trimOrNull(lastEtag);
            boolean etagComparable = StringUtils.hasText(etag) && StringUtils.hasText(previousEtag);
            boolean unchanged = etagComparable
                    ? etag.equals(previousEtag)
                    : StringUtils.hasText(headLastModified) && headLastModified.equals(trimOrNull(lastModified));
            if (unchanged) {
                return RemoteFetchResult.skipped("远程文件未变化", etag, headLastModified, lastContentHash);
            }
        }

        Path tempFile = null;
        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            tempFile = Files.createTempFile("knowledge-schedule-", ".tmp");
            CopyResult copyResult = copyWithLimitAndDigest(response.bodyStream(), tempFile, maxBytes);
            if (copyResult.size == 0) {
                deleteTempFileQuietly(tempFile);
                throw new ClientException("远程文件内容为空");
            }

            String hash = copyResult.sha256Hex;
            String etag = firstHasText(trimOrNull(response.etag()), headResponse == null ? null : trimOrNull(headResponse.etag()), null);
            String fetchLastModified = firstHasText(trimOrNull(response.lastModified()), headResponse == null ? null : trimOrNull(headResponse.lastModified()), null);

            if (StringUtils.hasText(hash) && hash.equals(trimOrNull(lastContentHash))) {
                deleteTempFileQuietly(tempFile);
                return RemoteFetchResult.skipped("内容哈希未变化", etag, fetchLastModified, hash);
            }

            String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : fallbackFileName;
            return RemoteFetchResult.changed(tempFile, copyResult.size, response.contentType(), fileName, hash, etag, fetchLastModified);
        } catch (IOException e) {
            deleteTempFileQuietly(tempFile);
            throw new ServiceException("远程文件拉取失败: " + e.getMessage());
        } catch (RuntimeException e) {
            deleteTempFileQuietly(tempFile);
            throw e;
        }
    }

    private HttpClientHelper.HttpHeadResponse tryHead(String url) {
        try {
            return httpClientHelper.head(url, Map.of());
        } catch (Exception e) {
            log.debug("HEAD 获取失败，改为直接下载: {}", url, e);
            return null;
        }
    }

    private void checkSizeLimit(long maxBytes, Long contentLength) {
        if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
            throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
        }
    }

    private StoredFileDTO uploadViaTemp(String bucketName, InputStream remoteStream, String fileName,
                                        String contentType, long maxBytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("knowledge-upload-", ".tmp");
            long size = copyWithLimit(remoteStream, tempFile, maxBytes);
            if (size == 0) {
                throw new ClientException("远程文件内容为空");
            }
            try (InputStream tempInputStream = Files.newInputStream(tempFile)) {
                return fileStorageService.upload(bucketName, tempInputStream, size, fileName, contentType);
            }
        } catch (IOException e) {
            throw new ServiceException("远程文件上传失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("删除远程上传临时文件失败: {}", tempFile, e);
                }
            }
        }
    }

    private long copyWithLimit(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        long total = 0;
        try (var outputStream = Files.newOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                }
                outputStream.write(buffer, 0, len);
            }
            return total;
        }
    }

    private CopyResult copyWithLimitAndDigest(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    total += len;
                    if (maxBytes > 0 && total > maxBytes) {
                        throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                    }
                    outputStream.write(buffer, 0, len);
                    digest.update(buffer, 0, len);
                }
            }
            return new CopyResult(total, hexEncode(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("SHA-256 算法不可用");
        }
    }

    private record CopyResult(long size, String sha256Hex) {
    }

    private static String hexEncode(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            String value = Integer.toHexString(0xff & b);
            if (value.length() == 1) {
                hex.append('0');
            }
            hex.append(value);
        }
        return hex.toString();
    }

    private void deleteTempFileQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("删除临时文件失败: {}", tempFile, e);
            }
        }
    }

    private String firstHasText(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static final class RemoteFetchResult implements AutoCloseable {

        private final boolean changed;
        private Path tempFile;
        private final long size;
        private final String contentType;
        private final String fileName;
        private final String contentHash;
        private final String etag;
        private final String lastModified;
        private final String message;

        private RemoteFetchResult(boolean changed, Path tempFile, long size, String contentType,
                                  String fileName, String contentHash, String etag,
                                  String lastModified, String message) {
            this.changed = changed;
            this.tempFile = tempFile;
            this.size = size;
            this.contentType = contentType;
            this.fileName = fileName;
            this.contentHash = contentHash;
            this.etag = etag;
            this.lastModified = lastModified;
            this.message = message;
        }

        public static RemoteFetchResult skipped(String message, String etag, String lastModified, String contentHash) {
            return new RemoteFetchResult(false, null, 0, null, null, contentHash, etag, lastModified, message);
        }

        public static RemoteFetchResult changed(Path tempFile, long size, String contentType, String fileName,
                                                 String contentHash, String etag, String lastModified) {
            return new RemoteFetchResult(true, tempFile, size, contentType, fileName, contentHash, etag, lastModified, null);
        }

        public boolean changed() { return changed; }
        public Path tempFile() { return tempFile; }
        public long size() { return size; }
        public String contentType() { return contentType; }
        public String fileName() { return fileName; }
        public String contentHash() { return contentHash; }
        public String etag() { return etag; }
        public String lastModified() { return lastModified; }
        public String message() { return message; }

        @Override
        public void close() {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // best-effort cleanup
                }
                tempFile = null;
            }
        }
    }
}
