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

package com.nageoffer.ai.ragent.infra.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LLMResponseCleanerTest {

    @Test
    void nullPassesThrough() {
        assertNull(LLMResponseCleaner.stripMarkdownCodeFence(null));
    }

    @Test
    void unfencedJsonIsPreserved() {
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence("  {\"value\":1}  "));
    }

    @Test
    void singleFencedBlockIsUnwrapped() {
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence("```json\n{\"value\":1}\n```"));
    }

    @Test
    void proseAfterClosingFenceIsDiscarded() {
        String raw = "```json\n{\"value\":1}\n```\nThis is the result.";
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence(raw));
    }

    @Test
    void laterFencedExplanationDoesNotBecomePartOfTheFirstBlock() {
        String raw = "```json\n{\"value\":1}\n```\nExplanation:\n```text\nAn example.\n```";
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence(raw));
    }

    @Test
    void adjacentFencedBlocksKeepOnlyTheFirstBlock() {
        String raw = "```json\n{\"value\":1}\n```\n```json\n{\"value\":2}\n```";
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence(raw));
    }

    @Test
    void closingFenceMayHaveHorizontalWhitespace() {
        String raw = "```json\n{\"value\":1}\n  ```\t\nExplanation:\n```text\nMore text.\n```";
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence(raw));
    }

    @Test
    void crlfFencedBlocksKeepOnlyTheFirstBlock() {
        String raw = "```json\r\n{\"value\":1}\r\n```\r\n```text\r\nMore text.\r\n```";
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence(raw));
    }

    @Test
    void backticksInsideJsonStringsArePreserved() {
        String json = "{\"marker\":\"```\"}";
        assertEquals(json, LLMResponseCleaner.stripMarkdownCodeFence("```json\n" + json + "\n```"));
    }

    @Test
    void unclosedBlockDoesNotTruncateBackticksInsideJsonStrings() {
        String json = "{\"marker\":\"```\"}";
        assertEquals(json, LLMResponseCleaner.stripMarkdownCodeFence("```json\n" + json));
    }

    @Test
    void unclosedOpeningFenceIsStillRemoved() {
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence("```json\n{\"value\":1}"));
    }

    @Test
    void trailingFenceWithoutAnOpeningFenceIsStillRemoved() {
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence("{\"value\":1}\n```"));
    }

    @Test
    void inlineTrailingFenceRemainsSupported() {
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence("```json\n{\"value\":1}```"));
    }

    @Test
    void singleLineFencedBlockRemainsSupported() {
        assertEquals("{\"value\":1}", LLMResponseCleaner.stripMarkdownCodeFence("```json {\"value\":1}```"));
    }

    @Test
    void emptyFencedBlockProducesEmptyContent() {
        assertEquals("", LLMResponseCleaner.stripMarkdownCodeFence("```json\n```"));
    }
}
