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

package com.nageoffer.ai.ragent.ingestion.util;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpClientHelperTest {

    private MockWebServer server;
    private HttpClientHelper httpClientHelper;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        httpClientHelper = new HttpClientHelper(new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    @DisplayName("Plain filename preserves literal plus signs")
    void quotedFilenamePreservesPlusSigns() {
        server.enqueue(responseWithDisposition("attachment; filename=\"C++ Guide.pdf\""));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/fallback.pdf").toString(), Map.of());

        assertEquals("C++ Guide.pdf", response.fileName());
    }

    @Test
    @DisplayName("RFC 5987 filename* uses its declared charset")
    void extendedFilenameIsDecoded() {
        server.enqueue(responseWithDisposition(
                "attachment; filename*=UTF-8''%E7%9F%A5%E8%AF%86%E5%BA%93%20%E6%8C%87%E5%8D%97.pdf"));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/fallback.pdf").toString(), Map.of());

        assertEquals("\u77e5\u8bc6\u5e93 \u6307\u5357.pdf", response.fileName());
    }

    @Test
    @DisplayName("filename* takes precedence over the compatibility filename")
    void extendedFilenameTakesPrecedence() {
        server.enqueue(responseWithDisposition(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''actual%20name.pdf"));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/path.pdf").toString(), Map.of());

        assertEquals("actual name.pdf", response.fileName());
    }

    @Test
    @DisplayName("URL path fallback decodes percent escapes without treating plus as space")
    void urlPathFilenamePreservesPlusSigns() {
        server.enqueue(new MockResponse.Builder().body("content").build());

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/C++%20Guide.pdf").toString(), Map.of());

        assertEquals("C++ Guide.pdf", response.fileName());
    }

    @Test
    @DisplayName("Empty extended filename falls back to the plain filename")
    void emptyExtendedFilenameFallsBack() {
        server.enqueue(responseWithDisposition(
                "attachment; filename=\"report.pdf\"; filename*=UTF-8''"));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/fallback.bin").toString(), Map.of());

        assertEquals("report.pdf", response.fileName());
    }

    @Test
    @DisplayName("Whitespace-only extended filename falls back on HEAD")
    void blankExtendedFilenameFallsBackOnHead() {
        server.enqueue(responseWithDisposition(
                "attachment; filename=\"report.pdf\"; filename*=UTF-8''%20"));

        HttpClientHelper.HttpHeadResponse response =
                httpClientHelper.head(server.url("/fallback.bin").toString(), Map.of());

        assertEquals("report.pdf", response.fileName());
    }

    @Test
    @DisplayName("Unusable filename parameters fall back to the URL path")
    void blankFilenamesFallBackToUrl() {
        server.enqueue(responseWithDisposition("attachment; filename=\" \"; filename*=UTF-8''%20"));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/path%20name.pdf").toString(), Map.of());

        assertEquals("path name.pdf", response.fileName());
    }

    @Test
    @DisplayName("A quoted parameter cannot inject a filename parameter")
    void quotedParameterDoesNotInjectFilename() {
        server.enqueue(responseWithDisposition(
                "attachment; filename=\"report.pdf\"; note=\"x; filename*=UTF-8''wrong.docx; y\""));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/fallback.bin").toString(), Map.of());

        assertEquals("report.pdf", response.fileName());
    }

    @Test
    @DisplayName("A quoted filename can contain semicolons and escaped quotes")
    void quotedFilenameKeepsSemicolonsAndEscapedQuotes() {
        server.enqueue(responseWithDisposition("attachment; filename=\"report; \\\"final\\\".pdf\""));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/fallback.bin").toString(), Map.of());

        assertEquals("report; \"final\".pdf", response.fileName());
    }

    @Test
    @DisplayName("A quoted parameter cannot inject a filename into stream downloads")
    void quotedParameterDoesNotInjectFilenameOnStream() {
        server.enqueue(responseWithDisposition(
                "attachment; filename=\"report.pdf\"; note=\"x; filename*=UTF-8''wrong.docx; y\""));

        try (HttpClientHelper.HttpFetchStream response =
                     httpClientHelper.openStream(server.url("/fallback.bin").toString(), Map.of(), 1024)) {
            assertEquals("report.pdf", response.fileName());
        }
    }

    @Test
    @DisplayName("An escaped quote inside another parameter does not expose a fake filename")
    void escapedQuoteDoesNotExposeFilenameParameter() {
        server.enqueue(responseWithDisposition(
                "attachment; filename=\"report.pdf\"; note=\"escaped \\\"; filename*=UTF-8''wrong.docx\""));

        HttpClientHelper.HttpFetchResponse response =
                httpClientHelper.get(server.url("/fallback.bin").toString(), Map.of());

        assertEquals("report.pdf", response.fileName());
    }

    private MockResponse responseWithDisposition(String disposition) {
        return new MockResponse.Builder()
                .setHeader("Content-Disposition", disposition)
                .body("content")
                .build();
    }
}
