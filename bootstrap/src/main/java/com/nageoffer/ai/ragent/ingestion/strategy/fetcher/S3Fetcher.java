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

package com.nageoffer.ai.ragent.ingestion.strategy.fetcher;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import com.nageoffer.ai.ragent.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;

/**
 * S3对象存储文档提取器
 * 支持从S3兼容的对象存储（如RustFS）中获取文档，示例路径：s3://biz/5fb28010e16c4083ab07ca41f29804b0.md
 */
@Component
@RequiredArgsConstructor
public class S3Fetcher implements DocumentFetcher {

    private final FileStorageService fileStorageService;

    @Override
    public SourceType supportedType() {
        return SourceType.S3;
    }

    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("S3路径不能为空");
        }

        if (!location.startsWith("s3://")) {
            throw new ServiceException("无效的S3路径格式，应以 s3:// 开头: " + location);
        }

        try {
            byte[] bytes;
            try (InputStream is = fileStorageService.openStream(location)) {
                bytes = is.readAllBytes();
            }

            String fileName = source.getFileName();
            if (!StringUtils.hasText(fileName)) {
                fileName = extractFileName(location);
            }

            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            return new FetchResult(bytes, mimeType, fileName);
        } catch (Exception e) {
            throw new ServiceException("从S3读取文件失败: " + location + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 从S3路径中提取文件名
     * 例如：s3://biz/5fb28010e16c4083ab07ca41f29804b0.md -> 5fb28010e16c4083ab07ca41f29804b0.md
     */
    private String extractFileName(String location) {
        int idx = location.lastIndexOf('/');
        return idx >= 0 ? location.substring(idx + 1) : location;
    }
}
