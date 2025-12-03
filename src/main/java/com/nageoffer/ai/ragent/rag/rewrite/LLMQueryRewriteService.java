package com.nageoffer.ai.ragent.rag.rewrite;


import com.nageoffer.ai.ragent.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.constant.RAGConstant;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
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
    private final QueryTermMappingService queryTermMappingService;

    @Override
    public String rewrite(String userQuestion) {
        // 功能开关关闭时：只做规则归一化，不走大模型
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            return queryTermMappingService.normalize(userQuestion);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        String prompt = RAGConstant.QUERY_REWRITE_PROMPT.formatted(normalizedQuestion);

        ChatRequest request = ChatRequest.builder()
                .prompt(prompt)
                .temperature(0.1D) // 把创造性压低
                .topP(0.3D)
                .thinking(false)
                .build();

        String result;
        try {
            result = llmService.chat(request);
        } catch (Exception e) {
            log.warn("查询改写调用失败，退回到规则归一化后的问题。question={}", userQuestion, e);
            return normalizedQuestion;
        }

        if (result == null || result.isBlank()) {
            return normalizedQuestion;
        }

        log.info("""
                RAG 查询改写：
                原始问题：{}
                归一化后：{}
                LLM 改写：{}
                """, userQuestion, normalizedQuestion, result);

        return result;
    }
}
