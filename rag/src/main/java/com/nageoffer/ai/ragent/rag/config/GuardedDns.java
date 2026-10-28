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

import okhttp3.Dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 文档抓取专用的 DNS：拒绝解析到保留地址的主机
 * <p>
 * 校验与建连共用 OkHttp 的同一次解析结果，因此不存在"请求前解析一次、建连时再解析一次"的
 * DNS rebinding 时间窗；重定向目标也会经过同一个 Dns
 */
public final class GuardedDns implements Dns {

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
        for (InetAddress address : addresses) {
            if (isReserved(address)) {
                throw new UnknownHostException("解析到保留地址，拒绝访问: " + hostname);
            }
        }
        return addresses;
    }

    private static boolean isReserved(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10 运营商级 NAT 段，多家云厂商的元数据服务落在这里（如 100.100.100.200）；
            // 这一段 isSiteLocalAddress() 为 false，必须单独判断
            return first == 100 && second >= 64 && second <= 127;
        }
        // IPv6 唯一本地地址 fc00::/7
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
