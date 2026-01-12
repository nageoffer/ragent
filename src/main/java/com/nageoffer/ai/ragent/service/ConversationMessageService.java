package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.service.bo.ConversationMessageBO;

import java.util.List;

public interface ConversationMessageService {

    /**
     * 新增对话消息
     *
     * @param conversationMessage 消息内容
     */
    void addMessage(ConversationMessageBO conversationMessage);

    List<ConversationMessageDO> listLatestMessages(String conversationId, String userId, Integer limit);
}
