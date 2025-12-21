package com.nageoffer.ai.ragent.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code true}
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * 改写时用于承接上下文的最大历史消息数
     */
    @Value("${rag.query-rewrite.max-history-messages:4}")
    private Integer queryRewriteMaxHistoryMessages;

    /**
     * 触发承接式改写的短问长度阈值
     */
    @Value("${rag.query-rewrite.short-query-threshold:12}")
    private Integer queryRewriteShortQueryThreshold;

    /**
     * 改写时用于承接上下文的最大字符数
     */
    @Value("${rag.query-rewrite.max-history-chars:500}")
    private Integer queryRewriteMaxHistoryChars;
}
