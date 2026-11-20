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

package com.nageoffer.ai.ragent.rag.core.storage;

import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class S3ObjectStorageClientTest {

    private MockWebServer server;
    private S3Presigner presigner;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        presigner = S3Presigner.builder()
                .endpointOverride(server.url("/").uri())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        presigner.close();
        server.close();
    }

    @Test
    @DisplayName("上传失败异常不泄露预签名 URL 的凭据查询参数")
    void uploadFailureDoesNotExposePresignedCredentials() {
        server.enqueue(new MockResponse.Builder().code(500).body("storage unavailable").build());
        S3ObjectStorageClient client = new S3ObjectStorageClient(
                mock(S3Client.class), presigner, new RagStorageProperties());

        Exception error = assertThrows(Exception.class, () -> client.streamPut(
                "documents",
                "kb/file.txt",
                new ByteArrayInputStream("data".getBytes()),
                4,
                "text/plain"));

        assertTrue(error.getMessage().contains("/documents/kb/file.txt"));
        assertFalse(error.getMessage().contains("?"));
        assertFalse(error.getMessage().contains("X-Amz-Credential"));
        assertFalse(error.getMessage().contains("X-Amz-Signature"));
        assertFalse(error.getMessage().contains("test-access-key"));
    }
}
