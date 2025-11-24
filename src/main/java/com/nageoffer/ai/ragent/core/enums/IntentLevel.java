package com.nageoffer.ai.ragent.core.enums;

public enum IntentLevel {

    /**
     * 顶层：集团信息化 / 业务系统 / 中间件环境信息
     */
    DOMAIN,

    /**
     * 第二层：人事 / 行政 / OA系统 / Redis ...
     */
    CATEGORY,

    /**
     * 第三层：更具体的 Topic，如 系统介绍 / 数据安全 / 架构设计
     */
    TOPIC
}
