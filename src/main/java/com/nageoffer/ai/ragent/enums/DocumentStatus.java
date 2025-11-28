package com.nageoffer.ai.ragent.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;


/**
 * 文档处理状态枚举
 *
 * <p>表示文档在处理过程中可能处于的各种状态
 */
@Getter
@RequiredArgsConstructor
public enum DocumentStatus {

    /**
     * 文档待处理
     */
    PENDING("pending"),

    /**
     * 文档处理中
     */
    RUNNING("running"),

    /**
     * 文档处理失败
     */
    FAILED("failed"),

    /**
     * 文档处理成功
     */
    SUCCESS("success");

    /**
     * 状态码
     */
    private final String code;
}
