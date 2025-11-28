package com.nageoffer.ai.ragent.service.rag.rewrite;


import com.nageoffer.ai.ragent.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.service.rag.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.nageoffer.ai.ragent.constant.RAGConstant.QUERY_REWRITE_PROMPT;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMQueryRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String rewrite(String userQuestion) {
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            return userQuestion;
        }

        String prompt = QUERY_REWRITE_PROMPT.formatted(userQuestion);

        ChatRequest request = ChatRequest.builder()
                .prompt(prompt)
                .thinking(false)
                .build();

        String result;
        try {
            result = llmService.chat(request);
        } catch (Exception e) {
            log.warn("查询改写调用失败，直接回退原始问题。question={}", userQuestion, e);
            return userQuestion;
        }

        if (result == null) {
            return userQuestion;
        }

        log.info("\nRAG查询原始问题：{}\n改写后：{}\n", userQuestion, result);
        return result;
    }
}
