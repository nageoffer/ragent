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

package com.nageoffer.ai.ragent.ingestion.util;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OutboundUrlGuard} 单元测试
 * <p>
 * 重点覆盖 Dns 层拦不住的那类目标：IP 字面量（回环 / 链路本地 / 站点本地 / CGN / IPv6 ULA）及其
 * 备选写法，不依赖任何外部网络
 */
class OutboundUrlGuardTest {

    /**
     * 字面量与回环：这些不经 Dns.lookup，只挂在自定义 Dns 上的防护对它们无效
     */
    @Test
    void blockLoopbackAndLocalLiterals() {
        List<String> blocked = List.of(
                "http://127.0.0.1/",
                "http://127.0.0.1:8080/admin",
                "http://127.1.2.3/",
                "http://0.0.0.0/",
                "http://[::1]/",
                "http://localhost/",
                "http://LOCALHOST/");
        for (String url : blocked) {
            assertThrows(ServiceException.class, () -> OutboundUrlGuard.validate(url), url);
        }
    }

    /**
     * 云元数据、内网段、CGN、IPv6 ULA、组播：都是「服务端代抓」不该到达的地方
     */
    @Test
    void blockReservedRanges() {
        List<String> blocked = List.of(
                "http://169.254.169.254/latest/meta-data/",
                "http://[fe80::1]/",
                "http://10.0.0.1/",
                "http://172.16.0.1/",
                "http://192.168.1.1/",
                "http://100.64.0.1/",
                "http://[fd00::1]/",
                "http://[fc00::1]/",
                "http://224.0.0.1/");
        for (String url : blocked) {
            assertThrows(ServiceException.class, () -> OutboundUrlGuard.validate(url), url);
        }
    }

    /**
     * 非 http/https 与畸形输入：解析不出就 fail closed，不能放行
     */
    @Test
    void blockNonHttpSchemeAndMalformedInput() {
        List<String> blocked = List.of(
                "ftp://example.com/file",
                "file:///etc/passwd",
                "不是链接",
                "http://",
                "");
        for (String url : blocked) {
            assertThrows(ServiceException.class, () -> OutboundUrlGuard.validate(url), url);
        }
        assertThrows(ServiceException.class, () -> OutboundUrlGuard.validate(null));
    }

    /**
     * 解析不了的 host 同样拒绝：判不了内外网就不能放行
     */
    @Test
    void blockUnresolvableHost() {
        assertThrows(ServiceException.class, () -> OutboundUrlGuard.validate("http://no-such-host.invalid/"));
    }

    /**
     * 公网 IP 字面量必须放行：这一层只拦内网，不做「只允许域名」的过严收敛
     */
    @Test
    void allowPublicIpLiteral() {
        assertDoesNotThrow(() -> OutboundUrlGuard.validate("http://93.184.216.34/index.html"));
        assertDoesNotThrow(() -> OutboundUrlGuard.validate("https://93.184.216.34/"));
    }

    /**
     * 接线验证：内网地址必须在发起 {@code newCall} 之前就被拒，不能让请求真的打到内网
     */
    @Test
    void httpClientHelperRejectsInternalUrlBeforeSendingRequest() {
        OkHttpClient client = mock(OkHttpClient.class);
        HttpClientHelper helper = new HttpClientHelper(client);

        assertThrows(ServiceException.class,
                () -> helper.get("http://169.254.169.254/latest/meta-data/", Map.of()));
        verify(client, never()).newCall(any(Request.class));
    }
}
