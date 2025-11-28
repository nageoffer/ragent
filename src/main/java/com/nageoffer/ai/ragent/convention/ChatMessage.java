package com.nageoffer.ai.ragent.convention;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息实体
 *
 * <p>
 * 用于统一抽象「大模型对话」中的一条消息，包含角色和消息内容：
 * <ul>
 *   <li>{@link Role#SYSTEM}：系统提示词，用于为大模型设定行为、规则</li>
 *   <li>{@link Role#USER}：用户输入消息</li>
 *   <li>{@link Role#ASSISTANT}：大模型（助手）回复内容</li>
 * </ul>
 * 该结构适合在不同模型/厂商之间做一层通用抽象
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息角色类型
     */
    public enum Role {
        /**
         * 系统角色，一般用于设定对话规则、身份设定、风格约束等
         */
        SYSTEM,

        /**
         * 用户角色，表示真实用户的提问或输入内容
         */
        USER,

        /**
         * 助手机器人角色，表示大模型返回的回复内容
         */
        ASSISTANT
    }

    /**
     * 当前消息的角色（系统 / 用户 / 助手）
     */
    private Role role;

    /**
     * 消息的具体文本内容
     */
    private String content;

    /**
     * 创建一条系统消息
     *
     * @param content 系统提示词内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#SYSTEM}
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    /**
     * 创建一条用户消息
     *
     * @param content 用户输入内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#USER}
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    /**
     * 创建一条助手消息
     *
     * @param content 助手回复内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}

