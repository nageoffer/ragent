package com.nageoffer.ai.ragent.service.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息业务对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessageBO {

    /**
     * 对话ID
     */
    private String conversationId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 角色：system/user/assistant
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;
}
