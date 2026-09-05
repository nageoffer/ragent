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

package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
 * LiteLLM embedding client.
 *
 * <p>LiteLLM Proxy exposes an OpenAI-compatible {@code /embeddings} endpoint,
 * so this client reuses the shared OpenAI-style embedding transport unchanged.
 */
@Service
public class LiteLLMEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public LiteLLMEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.LITE_LLM.getId();
    }

    @Override
    protected int maxBatchSize() {
        return 32;
    }
}
