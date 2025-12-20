package com.nageoffer.ai.ragent.rag.rewrite;

import java.util.List;

public class RewriteResult {

    private final String rewrittenQuestion;
    private final List<String> subQuestions;

    public RewriteResult(String rewrittenQuestion, List<String> subQuestions) {
        this.rewrittenQuestion = rewrittenQuestion;
        this.subQuestions = subQuestions;
    }

    public String rewrittenQuestion() {
        return rewrittenQuestion;
    }

    public List<String> subQuestions() {
        return subQuestions;
    }

    public String joinSubQuestions() {
        StringBuilder sb = new StringBuilder();
        if (rewrittenQuestion != null && !rewrittenQuestion.isBlank()) {
            sb.append(rewrittenQuestion.trim()).append("\n");
        }
        return sb.toString();
    }
}
