package com.nageoffer.ai.ragent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.memory")
@Validated
public class MemoryProperties {

    /**
     * 短期记忆最大轮数（user+assistant 视为一轮）
     */
    @Min(1)
    @Max(100)
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

    /**
     * 摘要最大字数
     */
    @Min(200)
    @Max(1000)
    private int summaryMaxChars = 200;

    /**
     * 会话标题最大长度（用于提示词约束）
     */
    private int titleMaxLength = 30;
}
