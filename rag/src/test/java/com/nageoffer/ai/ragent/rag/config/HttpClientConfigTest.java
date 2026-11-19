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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jev 客户端必须用独立连接池
 * <p>
 * 与 syncHttpClient 共池时，Jev 要和它的多个使用方争抢 5 个全局空闲槽位，而两边 host 不重叠、无复用收益
 * 必须在容器里取 bean：{@code @Configuration} 走 CGLIB 代理，直接调方法每次都 new，测不出共池与否
 */
class HttpClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HttpClientConfig.class);

    @Test
    @DisplayName("jevHttpClient 的连接池与其余客户端相互独立，不争抢空闲槽位")
    void jevClientOwnsIsolatedPool() {
        runner.run(context -> {
            OkHttpClient jev = context.getBean("jevHttpClient", OkHttpClient.class);
            OkHttpClient sync = context.getBean("syncHttpClient", OkHttpClient.class);
            OkHttpClient streaming = context.getBean("streamingHttpClient", OkHttpClient.class);

            assertThat(jev.connectionPool()).isNotSameAs(sync.connectionPool());
            assertThat(jev.connectionPool()).isNotSameAs(streaming.connectionPool());
        });
    }

    @Test
    @DisplayName("Jev 的超时预算按实测定，与 syncHttpClient 的宽预算分开")
    void jevClientUsesItsOwnTimeouts() {
        runner.run(context -> {
            OkHttpClient jev = context.getBean("jevHttpClient", OkHttpClient.class);

            assertThat(jev.connectTimeoutMillis()).isEqualTo(2_000);
            assertThat(jev.readTimeoutMillis()).isEqualTo(5_000);
            assertThat(jev.callTimeoutMillis()).isEqualTo(5_000);
            assertThat(context.getBean("syncHttpClient", OkHttpClient.class).callTimeoutMillis())
                    .isEqualTo(45_000);
        });
    }
}
