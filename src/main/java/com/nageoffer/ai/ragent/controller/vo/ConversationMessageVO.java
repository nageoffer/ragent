package com.nageoffer.ai.ragent.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话消息视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessageVO {

    /**
     * 消息ID
     */
    private Long id;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 角色 (如: user, assistant)
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 创建时间
     */
    private Date createTime;
}
