/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * HTTP 客户端配置类
 */
@Configuration
public class HttpClientConfig {

    /**
     * 流式 HTTP 客户端（Primary）
     */
    @Bean
    @Primary
    public OkHttpClient streamingHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ZERO)
                .callTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 同步 HTTP 客户端
     */
    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 文档抓取专用客户端：拒绝解析到保留地址，且不跟随重定向
     * <p>
     * 只给 ingestion 的抓取链路使用（HttpClientHelper）。不要把这些限制加到 syncHttpClient 上：
     * 会话 / 向量 / 重排 / VLM 的上游可能是本机或内网地址（出厂配置里 Ollama 就是
     * http://localhost:11434），保留地址校验会把这些调用一起打死
     */
    @Bean
    public OkHttpClient documentFetchHttpClient(@Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
        return syncHttpClient.newBuilder()
                .followRedirects(false)
                .dns(new GuardedDns())
                .build();
    }
}
