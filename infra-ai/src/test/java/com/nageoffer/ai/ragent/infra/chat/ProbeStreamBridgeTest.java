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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeStreamBridgeTest {

    @Test
    void whitespaceShouldRemainBufferedUntilTextArrives() throws Exception {
        RecordingCallback downstream = new RecordingCallback();
        ProbeStreamBridge bridge = new ProbeStreamBridge(downstream);

        bridge.onContent("\n\n");

        assertFalse(bridge.awaitFirstPacket(10, TimeUnit.MILLISECONDS).isSuccess());
        assertTrue(downstream.contents.isEmpty());

        bridge.onContent("正文");

        assertTrue(bridge.awaitFirstPacket(1, TimeUnit.SECONDS).isSuccess());
        assertEquals(List.of("\n\n", "正文"), downstream.contents);
    }

    @Test
    void whitespaceOnlyStreamShouldStillBeTreatedAsNoContent() throws Exception {
        RecordingCallback downstream = new RecordingCallback();
        ProbeStreamBridge bridge = new ProbeStreamBridge(downstream);

        bridge.onContent("   ");
        bridge.onComplete();

        assertFalse(bridge.awaitFirstPacket(1, TimeUnit.SECONDS).isSuccess());
        assertTrue(downstream.contents.isEmpty());
        assertFalse(downstream.completed);
    }

    private static final class RecordingCallback implements StreamCallback {

        private final List<String> contents = new ArrayList<>();
        private boolean completed;

        @Override
        public void onContent(String content) {
            contents.add(content);
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable error) {
        }
    }
}
