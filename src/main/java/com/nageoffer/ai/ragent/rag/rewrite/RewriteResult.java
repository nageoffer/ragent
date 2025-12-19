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
        sb.append("## 用户问题\n");
        if (rewrittenQuestion != null && !rewrittenQuestion.isBlank()) {
            sb.append(rewrittenQuestion.trim()).append("\n\n");
        }
        sb.append("### 子问句（逐条回答）\n");
        if (subQuestions != null && !subQuestions.isEmpty()) {
            for (int i = 0; i < subQuestions.size(); i++) {
                sb.append(i + 1).append(". ").append(subQuestions.get(i)).append("\n");
            }
        } else {
            sb.append("1. ").append(rewrittenQuestion != null ? rewrittenQuestion : "").append("\n");
        }
        return sb.toString();
    }
}
