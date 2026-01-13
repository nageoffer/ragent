package com.nageoffer.ai.ragent.service.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话摘要业务对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationSummaryBO {

    /**
     * 会话 ID
     */
    private String conversationId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 摘要内容
     */
    private String content;

    /**
     * 摘要覆盖的最后一条消息 ID
     */
    private Long lastMessageId;

    /**
     * 摘要时间
     */
    private Date summaryTime;
}
