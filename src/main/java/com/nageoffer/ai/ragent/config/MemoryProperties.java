package com.nageoffer.ai.ragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.memory")
public class MemoryProperties {

    /**
     * 短期记忆最大轮数（user+assistant 视为一轮）
     */
    private int maxTurns = 5;

    /**
     * 记忆过期时间（分钟）
     */
    private int ttlMinutes = 60;
}
