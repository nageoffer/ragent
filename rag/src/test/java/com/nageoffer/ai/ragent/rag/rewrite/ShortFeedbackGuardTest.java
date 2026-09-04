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

package com.nageoffer.ai.ragent.rag.rewrite;

import com.nageoffer.ai.ragent.rag.core.rewrite.ShortFeedbackGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortFeedbackGuardTest {

    @Test
    void shouldDetectThanksFeedback() {
        assertTrue(ShortFeedbackGuard.isShortFeedback("谢谢，回答很清楚"));
        assertTrue(ShortFeedbackGuard.isShortFeedback("谢谢"));
        assertTrue(ShortFeedbackGuard.isShortFeedback("感谢"));
    }

    @Test
    void shouldDetectAcknowledgementAndPraise() {
        assertTrue(ShortFeedbackGuard.isShortFeedback("好的"));
        assertTrue(ShortFeedbackGuard.isShortFeedback("明白了"));
        assertTrue(ShortFeedbackGuard.isShortFeedback("收到了"));
        assertTrue(ShortFeedbackGuard.isShortFeedback("很好"));
        assertTrue(ShortFeedbackGuard.isShortFeedback("没问题"));
    }

    @Test
    void shouldNotBypassRealQuestions() {
        assertFalse(ShortFeedbackGuard.isShortFeedback("你能帮我做什么？"));
        assertFalse(ShortFeedbackGuard.isShortFeedback("怎么配置超时"));
        assertFalse(ShortFeedbackGuard.isShortFeedback("什么是 RAG"));
        assertFalse(ShortFeedbackGuard.isShortFeedback("请帮我查一下文档"));
    }

    @Test
    void shouldNotBypassLongMessages() {
        assertFalse(ShortFeedbackGuard.isShortFeedback("谢谢你的回答，另外我想知道淘宝和天猫的数据安全是怎么做的"));
        assertFalse(ShortFeedbackGuard.isShortFeedback("好的，那接下来详细讲讲知识库分块的流程和参数配置"));
    }

    @Test
    void shouldNotBypassBlankInput() {
        assertFalse(ShortFeedbackGuard.isShortFeedback(null));
        assertFalse(ShortFeedbackGuard.isShortFeedback(""));
        assertFalse(ShortFeedbackGuard.isShortFeedback("   "));
    }
}
