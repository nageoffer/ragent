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

package com.nageoffer.ai.ragent.integration;

import com.nageoffer.ai.ragent.rag.config.RestFSS3Config;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.impl.S3FileStorageService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("real-it")
@EnabledIfSystemProperty(named = RealIntegrationTestSupport.ENABLED_PROPERTY, matches = "true")
class RustFsRealIntegrationTest {

    @Test
    void uploadReadAndDeleteSmallObjectThroughRustFs() throws Exception {
        String endpoint = RealIntegrationTestSupport.value(
                "rustfs.url",
                "RAGENT_RUSTFS_URL",
                "http://" + RealIntegrationTestSupport.DEFAULT_TAILSCALE_HOST + ":9000");
        String accessKey = RealIntegrationTestSupport.value(
                "rustfs.access-key-id",
                "RAGENT_RUSTFS_ACCESS_KEY_ID",
                "rustfsadmin");
        String secretKey = RealIntegrationTestSupport.value(
                "rustfs.secret-access-key",
                "RAGENT_RUSTFS_SECRET_ACCESS_KEY",
                "rustfsadmin");
        String bucket = RealIntegrationTestSupport.value(
                "ragent.real-it.rustfs.bucket",
                "RAGENT_REAL_IT_RUSTFS_BUCKET",
                "ragent-real-it");

        RestFSS3Config config = new RestFSS3Config();
        try (S3Client s3Client = config.s3Client(endpoint, accessKey, secretKey);
             S3Presigner presigner = config.s3Presigner(endpoint, accessKey, secretKey)) {
            ensureBucketExists(s3Client, bucket);
            S3FileStorageService storageService = new S3FileStorageService(s3Client, presigner);

            byte[] content = ("ragent real rustfs test\n" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
            StoredFileDTO stored = storageService.upload(bucket, content, "real-rustfs-test.txt", "text/plain");

            try {
                assertThat(stored.getUrl()).startsWith("s3://" + bucket + "/");
                assertThat(stored.getOriginalFilename()).isEqualTo("real-rustfs-test.txt");
                assertThat(stored.getSize()).isEqualTo(content.length);

                try (InputStream in = storageService.openStream(stored.getUrl())) {
                    assertThat(in.readAllBytes()).isEqualTo(content);
                }
            } finally {
                if (stored.getUrl() != null) {
                    storageService.deleteByUrl(stored.getUrl());
                }
            }
        }
    }

    private void ensureBucketExists(S3Client s3Client, String bucket) {
        try {
            s3Client.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException ex) {
            s3Client.createBucket(b -> b.bucket(bucket));
        }
    }
}
