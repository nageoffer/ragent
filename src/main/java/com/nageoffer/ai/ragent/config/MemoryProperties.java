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
     * 缓存过期时间（分钟）
     */
    private int ttlMinutes = 60;

    /**
     * 是否启用对话记忆压缩
     */
    private boolean summaryEnabled = false;

    /**
     * 触发摘要的轮数阈值
     */
    private int summaryTriggerTurns = 12;
}
