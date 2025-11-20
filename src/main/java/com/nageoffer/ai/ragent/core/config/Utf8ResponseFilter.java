package com.nageoffer.ai.ragent.config;

import jakarta.servlet.*;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class Utf8ResponseFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        // 强制设置字符编码为 UTF-8
        response.setCharacterEncoding("UTF-8");
        
        chain.doFilter(request, response);
    }
}
