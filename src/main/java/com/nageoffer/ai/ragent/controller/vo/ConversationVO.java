package com.nageoffer.ai.ragent.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationVO {

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 最后活动时间
     */
    private Date lastTime;
}
