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

        RewriteResult result = multiQuestionRewriteService.rewriteAndSplit(question);

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
