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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 抓取链路的出站目标校验：保留地址一律拒绝，公网地址放行
 * <p>
 * 用例全部使用 IP 字面量，InetAddress 直接解析、不产生 DNS 查询，可离线运行
 */
class GuardedDnsTest {

    private final GuardedDns guardedDns = new GuardedDns();

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "169.254.169.254", "10.0.0.7", "172.16.0.1",
            "192.168.1.5", "100.64.0.1", "100.100.100.200", "100.127.255.254",
            "::1", "fc00::1", "fd00::1", "fe80::1"})
    void rejectsReservedAddress(String host) {
        assertThrows(UnknownHostException.class, () -> guardedDns.lookup(host));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "11.0.0.1", "100.63.255.255", "100.128.0.1",
            "2001:4860:4860::8888"})
    void allowsPublicAddress(String host) throws UnknownHostException {
        assertFalse(guardedDns.lookup(host).isEmpty());
    }
}
