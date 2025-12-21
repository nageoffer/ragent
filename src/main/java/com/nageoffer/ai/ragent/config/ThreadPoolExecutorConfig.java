package com.nageoffer.ai.ragent.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ThreadPoolExecutorConfig {

    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    @Bean
    public Executor mcpBatchThreadPoolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
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
        return TtlExecutors.getTtlExecutor(executor);
    }

    @Bean
    public Executor ragContextThreadPoolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                4,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_context_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    @Bean
    public Executor ragRetrievalThreadPoolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT,
                CPU_COUNT << 1,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_retrieval_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 意图识别并行执行线程池
     */
    @Bean
    public Executor intentClassifyThreadPoolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT,
                CPU_COUNT << 1,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("intent_classify_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }
}
