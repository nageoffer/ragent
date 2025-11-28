package com.nageoffer.ai.ragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 重排序（Rerank）模型配置
 *
 * <p>
 * 用于统一管理 RAG 检索召回后的「重排模型」配置，
 * 可通过 {@code ai.rerank.provider} 动态选择不同厂商的 Rerank 服务
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * ai:
 *   rerank:
 *     provider: ollama   # 或 bailian
 *     ollama:
 *       url: http://localhost:11434
 *       api-key: xxx
 *       model: bge-reranker-v2-m3
 *     bailian:
 *       url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *       api-key: xxx
 *       model: qilin-rerank
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.rerank")
public class RerankProperties {

    /**
     * 当前使用的重排服务提供方标识
     * <p>
     * 例如：
     * <ul>
     *   <li>{@code ollama}：本地 / 私有化部署的 Ollama Rerank 模型</li>
     *   <li>{@code bailian}：阿里百炼 / DashScope 提供的重排模型</li>
     * </ul>
     * 业务代码可根据该值路由到不同的重排实现
     */
    private String provider;

    /**
     * 使用 Ollama 作为重排服务时的配置
     * <p>
     * 当 {@link #provider} = {@code ollama} 时生效
     */
    private ChatOllamaProperties ollama;

    /**
     * 使用阿里百炼（DashScope）作为重排服务时的配置
     * <p>
     * 当 {@link #provider} = {@code bailian} 时生效
     */
    private ChatBaiLianProperties bailian;

    /**
     * Ollama Rerank 配置
     *
     * @param url    Ollama 服务访问地址，例如 {@code http://localhost:11434}
     * @param apiKey 调用 Ollama 的 API Key（如启用鉴权时必填）
     * @param model  默认使用的重排模型名称，例如 {@code bge-reranker-v2-m3}
     */
    public record ChatOllamaProperties(String url, String apiKey, String model) {
    }

    /**
     * 百炼（阿里 DashScope）Rerank 配置
     *
     * @param url    百炼兼容 OpenAI 协议的网关地址
     * @param apiKey 百炼平台发放的 API Key
     * @param model  默认使用的重排模型名称，例如 {@code qilin-rerank} 等
     */
    public record ChatBaiLianProperties(String url, String apiKey, String model) {
    }
}

