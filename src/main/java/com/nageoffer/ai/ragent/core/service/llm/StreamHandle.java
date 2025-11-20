package com.nageoffer.ai.ragent.core.service.llm;

/**
 * 流式控制句柄，用于中途取消（abort）
 */
interface StreamHandle {

    /**
     * 请求取消当前流式调用
     */
    void cancel();
}