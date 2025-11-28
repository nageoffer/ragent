package com.nageoffer.ai.ragent.framework.errorcode;

/**
 * 平台错误码
 * 定义错误码抽象接口，由各错误码类实现接口方法
 */
public interface IErrorCode {

    /**
     * 错误码
     */
    String code();

    /**
     * 错误信息
     */
    String message();
}
