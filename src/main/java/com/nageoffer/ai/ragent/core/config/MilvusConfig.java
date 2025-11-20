package com.nageoffer.ai.ragent.core.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient(@Value("${milvus.uri}") String uri, @Value("${milvus.token:}") String token) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(uri);

        if (token != null && !token.isEmpty()) {
            builder.token(token);
        }

        return new MilvusClientV2(builder.build());
    }
}