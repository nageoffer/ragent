package com.nageoffer.ai.ragent.core.service.llm;

public interface StreamCallback {

    /**
     * 每一次增量内容
     *
     * @param content
     */
    void onContent(String content);

    /**
     * 整个回答结束
     */
    void onComplete();

    /**
     * 出错
     *
     * @param error
     */
    void onError(Throwable error);
}