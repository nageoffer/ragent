package com.nageoffer.ai.ragent.rag.rewrite;

import java.util.List;

/**
 * 用户查询改写：将自然语言问题改写成适合 RAG 检索的查询语句
 */
public interface QueryRewriteService {

    /**
     * 将用户问题改写为适合向量 / 关键字检索的简洁查询
     *
     * @param userQuestion 原始用户问题
     * @return 改写后的检索查询（如果改写失败，则回退原问题）
     */
    String rewrite(String userQuestion);

    /**
     * 可选：改写 + 拆分多问句。
     * 默认实现仅返回改写结果并将其作为单个子问题。
     */
    default RewriteResult rewriteWithSplit(String userQuestion) {
        String rewritten = rewrite(userQuestion);
        return new RewriteResult(rewritten, List.of(rewritten));
    }

    /**
     * 改写与拆分结果封装
     */
    record RewriteResult(String rewrittenQuestion, List<String> subQuestions) {

        public String joinSubQuestions() {
            if (subQuestions == null || subQuestions.isEmpty()) {
                return "";
            }

            // 如果只有一个子问题，直接返回
            if (subQuestions.size() == 1) {
                return subQuestions.get(0);
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < subQuestions.size(); i++) {
                sb.append(i + 1).append(". ").append(subQuestions.get(i));
                if (i < subQuestions.size() - 1) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }
}