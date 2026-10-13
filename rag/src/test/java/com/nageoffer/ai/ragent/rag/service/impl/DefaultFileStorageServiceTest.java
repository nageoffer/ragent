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

import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DefaultFileStorageServiceTest {

    private final DefaultFileStorageService service = new DefaultFileStorageService(
            mock(ObjectStorageClient.class), mock(RedissonClient.class), new RagStorageProperties());

    @Test
    void objectKeyUsesExtensionFromFilenameRatherThanParentDirectory() {
        assertKeyMatches("folder.v1/report", "kb/[0-9a-f]{32}");
        assertKeyMatches("folder.v1\\report.pdf", "kb/[0-9a-f]{32}\\.pdf");
        assertKeyMatches("report.pdf/preview", "kb/[0-9a-f]{32}");
    }

    private void assertKeyMatches(String originalFilename, String expectedPattern) {
        StoredFileDTO stored = service.upload("kb", new byte[]{1}, originalFilename, "application/pdf");
        assertTrue(stored.getUrl().matches(expectedPattern), stored.getUrl());
    }
}
