package com.nageoffer.ai.ragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 向量（Embedding）模型配置
 *
 * <p>
 * 用于统一管理向量化相关的大模型配置，例如给 RAG 系统做文档/问题向量化
 * 通过 {@code ai.embedding.provider} 指定当前使用的向量服务提供方
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * ai:
 *   embedding:
 *     provider: ollama
 *     ollama:
 *       url: http://localhost:11434
 *       model: qwen3-embedding:8b
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.embedding")
public class EmbeddingProperties {

    /**
     * 当前使用的向量服务提供方标识
     * <p>
     * 例如：
     * <ul>
     *   <li>{@code ollama}：使用本地 / 私有化部署的 Ollama 进行向量化</li>
     *   <li>预留其他厂商：如 openai、dashscope 等，可后续扩展</li>
     * </ul>
     * 业务代码可根据该值选择对应的向量服务实现。
     */
    private String provider;

    /**
     * Ollama 向量服务配置
     * <p>
     * 当 {@link #provider} 设置为 {@code ollama} 时生效。
     */
    private EmbeddingOllamaProperties ollama;

    /**
     * SiliconFlow 向量服务配置
     * <p>
     * 当 {@link #provider} 设置为 {@code siliconFlow} 时生效。
     */
    private EmbeddingSiliconFlowProperties siliconFlow;

    /**
     * Ollama 向量模型配置
     *
     * @param url   Ollama 服务访问地址，例如 {@code http://localhost:11434}
     * @param model 默认使用的向量模型名称，例如 {@code qwen3-embedding:8b}
     */
    public record EmbeddingOllamaProperties(String url, String model) {
    }

    /**
     * SiliconFlow 向量模型配置
     *
     * @param url       SiliconFlow 服务访问地址
     * @param apiKey    SiliconFlow API 密钥，用于身份验证
     * @param model     默认使用的向量模型名称
     * @param dimension 向量维度，指定生成向量的维数
     */
    public record EmbeddingSiliconFlowProperties(String url, String apiKey, String model, Integer dimension) {
    }
}

