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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentControllerTest {

    private final KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final KnowledgeDocumentController controller = new KnowledgeDocumentController(
            documentService, fileStorageService, mock(IngestionSpecSchemaProvider.class));

    @Test
    void fileShouldExposeUnicodeNameThroughContentDisposition() throws Exception {
        String filename = "季度报告 2026.pdf";

        MockHttpServletResponse response = download(filename);

        String disposition = response.getHeader("Content-Disposition");
        assertTrue(disposition.contains("filename*=UTF-8''"));
        assertEquals(filename, ContentDisposition.parse(disposition).getFilename());
    }

    @Test
    void fileShouldPreserveSpacesAndLiteralPlusSigns() throws Exception {
        String filename = "C++ Guide.pdf";

        MockHttpServletResponse response = download(filename);

        assertEquals(filename,
                ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename());
    }

    private MockHttpServletResponse download(String filename) throws Exception {
        KnowledgeDocumentVO document = new KnowledgeDocumentVO();
        document.setDocName(filename);
        document.setFileType("pdf");
        document.setFileUrl("kb/document.pdf");
        when(documentService.get("doc-1")).thenReturn(document);
        when(fileStorageService.openStream(document.getFileUrl()))
                .thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.file("doc-1", response);
        return response;
    }
}
