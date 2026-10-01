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

package com.nageoffer.ai.ragent.framework.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.AbstractException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Optional;

/**
 * 全局异常处理器
 * 拦截指定异常并通过优雅构建方式返回前端信息
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private String maxRequestSize;

    /**
     * 拦截参数验证异常
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> validExceptionHandler(HttpServletRequest request,
                                                             HttpServletResponse response,
                                                             MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        String exceptionStr = Optional.of(firstFieldError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return jsonError(response, Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr));
    }

    /**
     * 拦截方法参数校验异常
     * 不接住会落到兜底分支，前端只能看到一句「系统执行出错」
     */
    @ExceptionHandler(value = HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> methodValidationExceptionHandler(HttpServletRequest request,
                                                                        HttpServletResponse response,
                                                                        HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("请求参数不合法");
        log.warn("[{}] {} [ex] {}", request.getMethod(), getUrl(request), message);
        return jsonError(response, Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message));
    }

    /**
     * 拦截应用内抛出的异常
     */
    @ExceptionHandler(value = {AbstractException.class})
    public ResponseEntity<Result<Void>> abstractException(HttpServletRequest request,
                                                         HttpServletResponse response,
                                                         AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex, ex.getCause());
            return jsonError(response, Results.failure(ex));
        }
        StringBuilder stackTraceBuilder = new StringBuilder();
        stackTraceBuilder.append(ex.getClass().getName()).append(": ").append(ex.getErrorMessage()).append("\n");
        StackTraceElement[] stackTrace = ex.getStackTrace();
        for (int i = 0; i < Math.min(5, stackTrace.length); i++) {
            stackTraceBuilder.append("\tat ").append(stackTrace[i]).append("\n");
        }
        log.error("[{}] {} [ex] {} \n\n{}", request.getMethod(), request.getRequestURL().toString(), ex, stackTraceBuilder);
        return jsonError(response, Results.failure(ex));
    }

    /**
     * 拦截未登录异常
     */
    @ExceptionHandler(value = NotLoginException.class)
    public ResponseEntity<Result<Void>> notLoginException(HttpServletRequest request,
                                                         HttpServletResponse response,
                                                         NotLoginException ex) {
        log.warn("[{}] {} [auth] not-login: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return jsonError(response, Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "未登录或登录已过期"));
    }

    /**
     * 拦截无角色权限异常
     */
    @ExceptionHandler(value = NotRoleException.class)
    public ResponseEntity<Result<Void>> notRoleException(HttpServletRequest request,
                                                        HttpServletResponse response,
                                                        NotRoleException ex) {
        log.warn("[{}] {} [auth] no-role: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return jsonError(response, Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "权限不足"));
    }

    /**
     * 拦截文件上传大小超限异常
     */
    @ExceptionHandler(value = MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> maxUploadSizeExceededException(HttpServletRequest request,
                                                                      HttpServletResponse response,
                                                                      MaxUploadSizeExceededException ex) {
        log.warn("[{}] {} [upload] 文件上传大小超限: {}", request.getMethod(), getUrl(request), ex.getMessage());
        String message;
        if (ex.getCause() instanceof IllegalStateException
                && ex.getCause().getCause() instanceof FileSizeLimitExceededException) {
            message = "上传文件大小超过限制，单个文件最大允许 " + maxFileSize;
        } else {
            message = "上传请求大小超过限制，单次请求最大允许 " + maxRequestSize;
        }
        return jsonError(response, Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message));
    }

    /**
     * 拦截未捕获异常
     */
    @ExceptionHandler(value = Throwable.class)
    public ResponseEntity<Result<Void>> defaultErrorHandler(HttpServletRequest request,
                                                           HttpServletResponse response,
                                                           Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return jsonError(response, Results.failure());
    }

    /**
     * 流建立前的错误明确返回 JSON，避免严格 SSE Accept 让 Result 找不到可写的媒体类型
     * 保留 HTTP 200 + 业务错误码的既有约定；流已提交后只记录异常，不往 SSE 里追加 JSON
     */
    private ResponseEntity<Result<Void>> jsonError(HttpServletResponse response, Result<Void> result) {
        if (response.isCommitted()) {
            return null;
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
    }

    private String getUrl(HttpServletRequest request) {
        if (StrUtil.isBlank(request.getQueryString())) {
            return request.getRequestURL().toString();
        }
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }
}
