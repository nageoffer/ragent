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

package com.nageoffer.ai.ragent.rag.core.source;

import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationContextEnricherTest {

    private final CitationContextEnricher enricher = enricher(true);

    private static CitationContextEnricher enricher(boolean citationEnabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);
        return new CitationContextEnricher(properties);
    }

    @Test
    void injectsSharedSourceIndexesAndRemovesInternalDocumentIds() {
        String context = """
                <content data-ragent-doc-id="doc-a">
                A
                </content>
                <content data-ragent-doc-id="doc-b">
                B
                </content>
                <content data-ragent-doc-id="doc-a">
                A2
                </content>
                """;
        List<SourceRef> sources = List.of(
                SourceRef.builder().index(1).docId("doc-b").build(),
                SourceRef.builder().index(2).docId("doc-a").build()
        );

        String result = enricher.enrich(context, sources);

        // 同一文档的多个块复用同一编号
        assertEquals(2, result.split("<content ref=\"2\">", -1).length - 1);
        assertTrue(result.contains("<content ref=\"1\">"));
        assertFalse(result.contains("data-ragent-doc-id"));
    }

    @Test
    void removesInternalIdWhenSourceWasNotRegistered() {
        String context = """
                <content data-ragent-doc-id="doc-x">
                X
                </content>
                """;

        String result = enricher.enrich(context, List.of());

        assertTrue(result.contains("<content>"));
        assertFalse(result.contains("data-ragent-doc-id"));
        assertFalse(result.contains(" ref="));
    }

    @Test
    void stripsInternalIdWithoutNumberingWhenCitationDisabled() {
        String context = """
                <content data-ragent-doc-id="doc-a">
                A
                </content>
                """;
        List<SourceRef> sources = List.of(SourceRef.builder().index(1).docId("doc-a").build());

        String result = enricher(false).enrich(context, sources);

        // 关闭引用：有来源也不注入编号，但内部 docId 仍必须抹掉
        assertTrue(result.contains("<content>"));
        assertFalse(result.contains("data-ragent-doc-id"));
        assertFalse(result.contains(" ref="));
    }
}
