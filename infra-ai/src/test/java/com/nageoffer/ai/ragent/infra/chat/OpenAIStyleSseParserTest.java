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

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIStyleSseParserTest {

    private static final Gson GSON = new Gson();

    @Test
    void normalContentShouldBeRecognizedAsContent() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}", GSON, false);
        assertTrue(event.hasContent());
        assertEquals("你好", event.content());
        assertFalse(event.completed());
    }

    @Test
    void emptyContentShouldNotBeRecognizedAsContent() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}", GSON, false);
        assertFalse(event.hasContent());
    }

    @Test
    void blankContentShouldNotBeRecognizedAsContent() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"   \"}}]}", GSON, false);
        assertFalse(event.hasContent());
    }

    @Test
    void completionWithoutContentShouldBeMarkedCompleted() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", GSON, false);
        assertFalse(event.hasContent());
        assertTrue(event.completed());
    }

    @Test
    void reasoningOnlyShouldNotBeRecognizedAsContent() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考中\"}}]}", GSON, true);
        assertTrue(event.hasReasoning());
        assertFalse(event.hasContent());
    }

    @Test
    void doneMarkerShouldBeRecognized() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine("data: [DONE]", GSON, false);
        assertFalse(event.hasContent());
        assertTrue(event.completed());
    }

    @Test
    void blankLineShouldReturnEmptyEvent() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine("", GSON, false);
        assertFalse(event.hasContent());
        assertFalse(event.hasReasoning());
        assertFalse(event.completed());
    }
}
