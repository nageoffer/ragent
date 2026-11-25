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

import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * zip 资源上限：超限在解包阶段就拒绝，不把内容读进堆
 */
class MinerUResultUnpackerZipLimitTest {

    private final MinerUResultUnpacker unpacker = new MinerUResultUnpacker(
            mock(FileStorageService.class), mock(VlmService.class), mock(ImageParseProperties.class));

    @Test
    void rejectsZipWithTooManyEntries() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i <= 2_000; i++) {
                zos.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zos.write('x');
                zos.closeEntry();
            }
        }

        ServiceException ex = assertThrows(ServiceException.class,
                () -> unpacker.unpack(out.toByteArray(), "test.zip", "doc-1"));

        // 抛出的应是上限异常本身，而不是被 readZip 的 catch (IOException) 包装成"解压失败"
        assertTrue(ex.getMessage().contains("条目数"), ex.getMessage());
    }
}
