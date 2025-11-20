package com.nageoffer.ai.ragent.core.convention;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单轮对话消息，用于统一表示 system / user / assistant 历史。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    private Role role;
    private String content;

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}
