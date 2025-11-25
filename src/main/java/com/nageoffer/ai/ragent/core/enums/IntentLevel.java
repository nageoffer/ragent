package com.nageoffer.ai.ragent.core.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IntentLevel {

    /**
     * 顶层：集团信息化 / 业务系统 / 中间件环境信息
     */
    DOMAIN(1),

    /**
     * 第二层：人事 / 行政 / OA系统 / Redis ...
     */
    CATEGORY(2),

    /**
     * 第三层：更具体的 Topic，如 系统介绍 / 数据安全 / 架构设计
     */
    TOPIC(3);

    private final int code;

    public int getCode() {
        return code;
    }

    public static IntentLevel fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (IntentLevel e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name();
    }
}
