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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class MultiQuestionRewriteServiceTests {

    private final MultiQuestionRewriteService multiQuestionRewriteService;

    @Test
    public void shouldReturnRewriteAndSubQuestions() {
        String question = "你好呀，淘宝和天猫数据安全怎么做的？";

        RewriteResult result = multiQuestionRewriteService.rewriteWithSplit(question);

        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.rewrittenQuestion());
        Assertions.assertFalse(result.rewrittenQuestion().isBlank());

        List<String> subs = result.subQuestions();
        Assertions.assertNotNull(subs);
        Assertions.assertFalse(subs.isEmpty());
        Assertions.assertTrue(subs.stream().allMatch(s -> s != null && !s.isBlank()));
        boolean hasTaobao = subs.stream().anyMatch(s -> s.contains("淘宝"));
        boolean hasTmall = subs.stream().anyMatch(s -> s.contains("天猫"));
        Assertions.assertTrue(hasTaobao && hasTmall, "期望子问题能覆盖并列主体：淘宝和天猫");
    }
}
