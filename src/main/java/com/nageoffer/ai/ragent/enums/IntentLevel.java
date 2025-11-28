package com.nageoffer.ai.ragent.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 意图层级枚举
 * 用于表示知识库中意图的层级结构
 */
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

    /**
     * 层级编码
     */
    private final int code;

    /**
     * 获取层级编码
     *
     * @return 层级编码值
     */
    public int getCode() {
        return code;
    }

    /**
     * 根据编码获取对应的意图层级
     *
     * @param code 层级编码
     * @return 对应的IntentLevel枚举值，如果code为null或不存在则返回null
     */
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

    /**
     * 返回枚举的名称
     *
     * @return 枚举名称字符串
     */
    @Override
    public String toString() {
        return name();
    }
}
