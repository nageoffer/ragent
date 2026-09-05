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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.infra.embedding.LiteLLMEmbeddingClient;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteLLMProviderTest {

    @Test
    void litellmProviderIdIsStable() {
        assertEquals("litellm", ModelProvider.LITE_LLM.getId());
        assertTrue(ModelProvider.LITE_LLM.matches("LiteLLM"));
    }

    @Test
    void chatAndEmbeddingClientsBindToLitellmProvider() {
        assertEquals(ModelProvider.LITE_LLM.getId(), new LiteLLMChatClient().provider());
        assertEquals(ModelProvider.LITE_LLM.getId(), new LiteLLMEmbeddingClient(new OkHttpClient()).provider());
    }
}
