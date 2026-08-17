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

import com.nageoffer.ai.ragent.user.config.SaTokenConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;

/**
 * Web MVC 配置
 *
 * <p>
 * 主要用于统一配置 Spring MVC 的消息转换器，确保字符串响应使用 UTF-8 编码，避免出现中文乱码或不同编码混用的问题
 * </p>
 *
 * <p>
 * 默认情况下，Spring Boot 会自动配置一组 HTTP 消息转换器，
 * 其中 {@link StringHttpMessageConverter} 的编码可能不是 UTF-8，通过此配置显式替换默认的字符串转换器
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class RagentWebMvcConfiguration implements WebMvcConfigurer {

    /**
     * 体验环境只读模式拦截器，属 rag 域概念，注册点收在本配置而非登录配置
     */
    private final DemoModeInterceptor demoModeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序介于登录与用户上下文之间，语义与原 SaTokenConfig 注册位置一致
        registry.addInterceptor(demoModeInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/error")
                .order(SaTokenConfig.ORDER_DEMO_MODE);
    }

    /**
     * 自定义消息转换器配置
     *
     * <p>
     * 这里通过 Spring Framework 7 的构建器替换默认 {@link StringHttpMessageConverter}，
     * 不影响 JSON 等其他默认转换器
     * </p>
     *
     * @param builder Spring MVC 消息转换器构建器
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        // 使用 UTF-8 作为字符串响应的默认编码
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);

        // 避免在响应的 Content-Type 头中自动添加 "charset" 列表（accept-charset），
        // 防止某些客户端或中间件对该头部解析不兼容
        stringConverter.setWriteAcceptCharset(false);

        // 替换默认 String 转换器，同时保留 JSON 等其余默认转换器
        builder.withStringConverter(stringConverter);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
