package com.nageoffer.ai.ragent.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    /**
     * 自定义 Milvus 客户端（用于原有的RagService）
     * 重命名为 customMilvusClient 避免与 Spring AI 的 milvusClient 冲突
     */
    @Bean(name = "customMilvusClient", destroyMethod = "close")
    public MilvusClientV2 customMilvusClient(@Value("${milvus.uri}") String uri, @Value("${milvus.token:}") String token) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(uri);

        if (token != null && !token.isEmpty()) {
            builder.token(token);
        }

        return new MilvusClientV2(builder.build());
    }
}