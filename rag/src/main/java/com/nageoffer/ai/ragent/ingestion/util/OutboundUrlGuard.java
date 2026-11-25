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
import okhttp3.HttpUrl;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 出站 URL 前置校验：所有承载不可信 URL 的抓取请求，都要在构建请求之前先过这里
 * <p>
 * 为什么必须在建请求之前拦一道：OkHttp 的 {@code RouteSelector} 对「本身就是 IP 字面量」的主机走
 * {@link InetAddress} 快路径，<b>不调用</b> {@code Dns.lookup}。因此只把校验挂在自定义 {@code Dns} 上时，
 * 主机名形态的内网目标拦得住，{@code http://127.0.0.1/}、{@code http://169.254.169.254/}（云元数据）
 * 这类字面量写法却会绕过连接层校验直达。本类在发请求前用与实际 client <b>同一个</b>解析器
 * （{@link HttpUrl}）做 canonicalization，把字面量及其等价变体在分类阶段就拦下，不依赖连接层 SPI 的覆盖面
 * <p>
 * 与连接层 Dns 复检互为补充：Dns 层负责兜底 DNS 重绑定（TOCTOU）——本层看到的公网 host 在真正连接时
 * 可能被解析到内网，那一跳只有连接层拦得住；本层则负责 Dns 层覆盖不到的 IP 字面量。两道都要在
 */
public final class OutboundUrlGuard {

    private OutboundUrlGuard() {
    }

    /**
     * 校验出站抓取目标，不合法直接抛异常（fail closed：解析不出、解析失败一律拒绝，不放行）
     *
     * @param rawUrl 调用方提供的原始链接，可能为空、非法或指向内网
     */
    public static void validate(String rawUrl) {
        HttpUrl url = canonicalize(rawUrl);
        String scheme = url.scheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ServiceException("仅支持 http/https 链接");
        }
        String host = url.host();
        for (InetAddress address : resolve(host)) {
            if (isInternal(address)) {
                throw new ServiceException("目标地址不允许访问: " + host);
            }
        }
    }

    /**
     * 用 OkHttp 自己的口径做 canonicalization
     * <p>
     * 复用 client 的解析器是为了让「校验时看到的 host」与「实际连接的 host」一致：自建正则或
     * {@code java.net.URI} 对 {@code 0x7f.1}、八进制、IPv6 括号等写法的归一化结果与 OkHttp 未必相同，
     * 两边的 host 判断一旦分叉，就会出现校验放行、连接打到别处的空档
     */
    private static HttpUrl canonicalize(String rawUrl) {
        HttpUrl url = rawUrl == null ? null : HttpUrl.parse(rawUrl);
        if (url == null) {
            throw new ServiceException("链接地址不合法");
        }
        return url;
    }

    /**
     * 解析 host 的全部地址
     * <p>
     * 解析不了就不能判定内外网，fail closed 直接拒；IP 字面量在这里不经 DNS，返回的就是它本身
     */
    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ServiceException("链接地址无法解析: " + host);
        }
    }

    private static boolean isInternal(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                || isCarrierGradeNat(address);
    }

    /**
     * IPv6 唯一本地地址 {@code fc00::/7}
     * <p>
     * JDK 的 {@code isSiteLocalAddress} 只认已废弃的 {@code fec0::/10}，现网在用的这个段它不覆盖
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * IPv4 运营商级 NAT {@code 100.64.0.0/10}（RFC 6598）
     * <p>
     * 与 {@code isSiteLocalAddress} 一样属于「非公网直连」段：自建集群的 Service 网段常落在这里，
     * JDK 同样不认为是私址
     */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 100
                && (bytes[1] & 0xC0) == 64;
    }
}
