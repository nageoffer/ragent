package com.nageoffer.ai.ragent.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ThreadPoolExecutorConfig {

    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    @Bean
    public ThreadPoolExecutor mcpBatchThreadPoolExecutor() {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                CPU_COUNT,
                CPU_COUNT << 1,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("mcp_batch_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return threadPoolExecutor;
    }
}
