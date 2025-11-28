package com.nageoffer.ai.ragent.framework.errorcode;

/**
 * 基础错误码定义枚举
 *
 * <p>
 * 定义了系统中常用的标准错误码，遵循阿里巴巴错误码规范：
 * <ul>
 *   <li>A 类错误：用户端错误（Client Error）</li>
 *   <li>B 类错误：系统执行错误（Service Error）</li>
 *   <li>C 类错误：第三方服务错误（Remote Error）</li>
 * </ul>
 * 通过组件包统一定义基础错误码，避免各服务重复定义相同内容。
 * </p>
 */
public enum BaseErrorCode implements IErrorCode {

    // ========== A 类错误：用户端错误 ==========

    /**
     * 一级宏观错误码：客户端错误
     */
    CLIENT_ERROR("A000001", "用户端错误"),

    // ========== A01 用户注册错误 ==========

    /**
     * 二级宏观错误码：用户注册错误
     */
    USER_REGISTER_ERROR("A000100", "用户注册错误"),

    /**
     * 用户名校验失败
     */
    USER_NAME_VERIFY_ERROR("A000110", "用户名校验失败"),

    /**
     * 用户名已存在
     */
    USER_NAME_EXIST_ERROR("A000111", "用户名已存在"),

    /**
     * 用户名包含敏感词
     */
    USER_NAME_SENSITIVE_ERROR("A000112", "用户名包含敏感词"),

    /**
     * 用户名包含特殊字符
     */
    USER_NAME_SPECIAL_CHARACTER_ERROR("A000113", "用户名包含特殊字符"),

    /**
     * 密码校验失败
     */
    PASSWORD_VERIFY_ERROR("A000120", "密码校验失败"),

    /**
     * 密码长度不够
     */
    PASSWORD_SHORT_ERROR("A000121", "密码长度不够"),

    /**
     * 手机号格式校验失败
     */
    PHONE_VERIFY_ERROR("A000151", "手机格式校验失败"),

    // ========== A02 幂等性错误 ==========

    /**
     * 幂等 Token 为空
     */
    IDEMPOTENT_TOKEN_NULL_ERROR("A000200", "幂等Token为空"),

    /**
     * 幂等 Token 已被使用或失效
     */
    IDEMPOTENT_TOKEN_DELETE_ERROR("A000201", "幂等Token已被使用或失效"),

    // ========== A03 查询参数错误 ==========

    /**
     * 查询数据量超过最大限制
     */
    SEARCH_AMOUNT_EXCEEDS_LIMIT("A000300", "查询数据量超过最大限制"),

    // ========== B 类错误：系统执行错误 ==========

    /**
     * 一级宏观错误码：系统执行出错
     */
    SERVICE_ERROR("B000001", "系统执行出错"),

    /**
     * 二级宏观错误码：系统执行超时
     */
    SERVICE_TIMEOUT_ERROR("B000100", "系统执行超时"),

    // ========== C 类错误：第三方服务错误 ==========

    /**
     * 一级宏观错误码：调用第三方服务出错
     */
    REMOTE_ERROR("C000001", "调用第三方服务出错");

    /**
     * 错误码
     */
    private final String code;

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 构造函数
     *
     * @param code    错误码
     * @param message 错误消息
     */
    BaseErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
