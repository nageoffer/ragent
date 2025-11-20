package com.nageoffer.ai.ragent.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 对话配置
 *
 * <p>
 * 统一管理 RAG / Chat 功能中用到的大模型服务配置，
 * 支持通过 {@code ai.chat.provider} 动态切换不同厂商
 * </p>
 *
 * <pre>
 * 示例配置：
 * ai:
 *   chat:
 *     provider: ollama   # 或 bailian
 *     ollama:
 *       url: http://localhost:11434
 *       api-key: xxx
 *       model: qwen2.5:14b
 *     bailian:
 *       url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *       api-key: xxx
 *       model: qwen-max
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.chat")
public class ChatProperties {

    /**
     * 当前使用的聊天服务提供方标识
     * <p>
     * 例如：
     * <ul>
     *   <li>{@code ollama}：本地 / 私有化部署 Ollama</li>
     *   <li>{@code bailian}：阿里百炼 / DashScope</li>
     * </ul>
     * 可以在业务代码中根据该值进行路由
     */
    private String provider;

    /**
     * Ollama 相关配置
     * <p>
     * 当 {@link #provider} = {@code ollama} 时生效
     */
    private ChatOllamaProperties ollama;

    /**
     * 百炼（阿里 DashScope）相关配置
     * <p>
     * 当 {@link #provider} = {@code bailian} 时生效
     */
    private ChatBaiLianProperties bailian;

    /**
     * Ollama 聊天配置
     *
     * @param url    Ollama 服务访问地址，例如 {@code http://localhost:11434}
     * @param apiKey 调用 Ollama 的 API Key（如果启用了鉴权，可为空代表未开启）
     * @param model  默认使用的模型名称，例如 {@code qwen2.5:14b}
     */
    public record ChatOllamaProperties(String url, String apiKey, String model) {
    }

    /**
     * 百炼（阿里 DashScope）聊天配置
     *
     * @param url    百炼兼容 OpenAI 协议的网关地址
     * @param apiKey 百炼平台发放的 API Key
     * @param model  默认使用的模型名称，例如 {@code qwen-max}、{@code qwen-long} 等
     */
    public record ChatBaiLianProperties(String url, String apiKey, String model) {
    }
}
