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

package com.nageoffer.ai.ragent.rag.memory;

import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.memory.DefaultForgettingController;
import com.nageoffer.ai.ragent.rag.core.memory.store.LongTermMemoryStore;
import com.nageoffer.ai.ragent.rag.core.memory.store.ShortTermMemoryStore;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DefaultForgettingControllerTests {

    @Test
    void shouldDecayShortAndLongTermMemories() {
        ShortTermMemoryStore shortTermMemoryStore = mock(ShortTermMemoryStore.class);
        LongTermMemoryStore longTermMemoryStore = mock(LongTermMemoryStore.class);
        MemoryProperties properties = new MemoryProperties();
        properties.setCleanupDecayThreshold(0.18D);
        properties.setLongTermDormantDays(45);
        properties.setLongTermDecayStep(0.07D);
        properties.setLongTermMinImportance(0.2D);

        DefaultForgettingController controller = new DefaultForgettingController(
                shortTermMemoryStore,
                longTermMemoryStore,
                properties
        );

        controller.execute();

        verify(shortTermMemoryStore).updateDecayScores(0.18D);
        verify(longTermMemoryStore).decayDormantMemories(45, 0.07D, 0.2D);
    }
}
