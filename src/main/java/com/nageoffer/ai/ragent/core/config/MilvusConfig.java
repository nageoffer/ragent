package com.nageoffer.ai.ragent.core.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端配置类
 *
 * <p>
 * 通过 Spring 容器统一创建并管理 {@link MilvusClientV2} 实例，用于向量数据的增删改查、索引管理等操作<br>
 * 支持通过配置文件设置连接地址与可选的访问令牌
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * milvus:
 *   uri: http://localhost:19530
 *   token: your-token-if-needed  # 可选，未开启鉴权时可以不配置或留空
 * </pre>
 */
@Configuration
public class MilvusConfig {

    /**
     * 构建 Milvus 客户端 Bean
     *
     * <p>
     * 使用 {@code @Bean(destroyMethod = "close")} 保证在 Spring 容器关闭时，
     * 自动调用 {@link MilvusClientV2#close()} 释放连接资源
     * </p>
     *
     * @param uri   Milvus 服务访问地址，例如 {@code http://localhost:19530} 或 gRPC 地址
     * @param token 访问 Milvus 的鉴权 Token，可为空；为空时不启用 Token 认证
     * @return 配置完成的 {@link MilvusClientV2} 客户端实例
     */
    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient(@Value("${milvus.uri}") String uri,
                                       @Value("${milvus.token:}") String token) {

        // 使用构建器模式创建 Milvus 连接配置
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(uri);

        // 如果配置了 token，则启用 Token 鉴权
        if (token != null && !token.isEmpty()) {
            builder.token(token);
        }

        // 创建并返回 Milvus 客户端
        return new MilvusClientV2(builder.build());
    }
}
