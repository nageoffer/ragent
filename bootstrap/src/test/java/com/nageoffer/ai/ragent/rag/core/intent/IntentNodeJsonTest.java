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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntentNodeJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void roundTripsCollectionNamesWithoutSerializingComputedProperty() throws Exception {
        IntentNode node = IntentNode.builder()
                .id("insurance")
                .collectionNames(List.of("insurance", " claims ", "insurance"))
                .build();

        String json = objectMapper.writeValueAsString(node);
        IntentNode restored = objectMapper.readValue(json, IntentNode.class);

        assertFalse(json.contains("effectiveCollectionNames"));
        assertEquals(List.of("insurance", "claims"), restored.getEffectiveCollectionNames());
    }

    @Test
    void ignoresComputedPropertyFromExistingCacheEntry() throws Exception {
        String json = """
                {
                  "id": "insurance",
                  "collectionNames": ["insurance"],
                  "effectiveCollectionNames": ["stale"]
                }
                """;

        IntentNode restored = objectMapper.readValue(json, IntentNode.class);

        assertEquals(List.of("insurance"), restored.getEffectiveCollectionNames());
    }
}
