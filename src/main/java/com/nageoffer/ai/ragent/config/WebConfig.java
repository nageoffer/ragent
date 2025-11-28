package com.nageoffer.ai.ragent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 配置
 *
 * <p>
 * 主要用于统一配置 Spring MVC 的消息转换器，确保字符串响应使用 UTF-8 编码，避免出现中文乱码或不同编码混用的问题
 * </p>
 *
 * <p>
 * 默认情况下，Spring Boot 会自动配置一组 {@link HttpMessageConverter}，
 * 其中 {@link StringHttpMessageConverter} 的编码可能不是 UTF-8，通过此配置可以显式设置为 UTF-8 并放到转换器链的最前面
 * </p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 自定义消息转换器配置
     *
     * <p>
     * 这里通过往转换器列表的首位插入一个 UTF-8 的 {@link StringHttpMessageConverter}，
     * 来覆盖默认的 String 类型消息转换行为
     * </p>
     *
     * @param converters Spring MVC 默认注册的消息转换器列表
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 使用 UTF-8 作为字符串响应的默认编码
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);

        // 避免在响应的 Content-Type 头中自动添加 "charset" 列表（accept-charset），
        // 防止某些客户端或中间件对该头部解析不兼容
        stringConverter.setWriteAcceptCharset(false);

        // 将自定义的 String 消息转换器放在列表首位，提高其匹配优先级
        converters.add(0, stringConverter);
    }
}

