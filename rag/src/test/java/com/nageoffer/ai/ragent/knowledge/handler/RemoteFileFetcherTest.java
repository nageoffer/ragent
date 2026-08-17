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

import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemoteFileFetcherTest {

    private static final String URL = "https://example.com/remote.txt";
    private static final String LAST_MODIFIED = "Tue, 21 Jul 2026 00:00:00 GMT";
    private static final byte[] NEW_CONTENT = "new remote content".getBytes();

    @Mock
    private HttpClientHelper httpClientHelper;

    @Mock
    private FileStorageService fileStorageService;

    private RemoteFileFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        fetcher = new RemoteFileFetcher(httpClientHelper, fileStorageService);
        Field maxFileSize = RemoteFileFetcher.class.getDeclaredField("maxFileSize");
        maxFileSize.setAccessible(true);
        maxFileSize.set(fetcher, DataSize.ofMegabytes(1));

        lenient().when(httpClientHelper.openStream(eq(URL), eq(Map.of()), anyLong()))
                .thenAnswer(invocation -> stream("etag-v2", LAST_MODIFIED, NEW_CONTENT));
    }

    @Test
    void shouldDownloadWhenEtagChangesEvenIfLastModifiedMatches() {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head("etag-v2", LAST_MODIFIED));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, "etag-v1", LAST_MODIFIED, "old-hash", "remote.txt")) {
            assertTrue(result.changed());
            assertEquals("etag-v2", result.etag());
        }

        verify(httpClientHelper).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    @Test
    void shouldSkipWhenEtagMatchesEvenIfLastModifiedChanges() {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head("etag-v1", "Tue, 21 Jul 2026 00:00:01 GMT"));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, "etag-v1", LAST_MODIFIED, "old-hash", "remote.txt")) {
            assertFalse(result.changed());
            assertEquals("远程文件未变化", result.message());
        }

        verify(httpClientHelper, never()).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    @Test
    void shouldFallbackToLastModifiedWhenEtagCannotBeCompared() {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head(null, LAST_MODIFIED));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, "etag-v1", LAST_MODIFIED, "old-hash", "remote.txt")) {
            assertFalse(result.changed());
            assertEquals("远程文件未变化", result.message());
        }

        verify(httpClientHelper, never()).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    @Test
    void shouldDownloadAndUseHashWhenValidatorsAreMissing() throws Exception {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head(null, null));
        String contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(NEW_CONTENT));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, null, null, contentHash, "remote.txt")) {
            assertFalse(result.changed());
            assertEquals("内容哈希未变化", result.message());
            assertEquals(contentHash, result.contentHash());
        }

        verify(httpClientHelper).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    private static HttpClientHelper.HttpHeadResponse head(String etag, String lastModified) {
        return new HttpClientHelper.HttpHeadResponse(etag, lastModified, "text/plain", (long) NEW_CONTENT.length, "remote.txt");
    }

    private static HttpClientHelper.HttpFetchStream stream(String etag, String lastModified, byte[] content) {
        Response response = new Response.Builder()
                .request(new Request.Builder().url(URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("text/plain"), content))
                .build();
        return new HttpClientHelper.HttpFetchStream(
                response,
                new ByteArrayInputStream(content),
                "text/plain",
                "remote.txt",
                etag,
                lastModified,
                (long) content.length);
    }
}
