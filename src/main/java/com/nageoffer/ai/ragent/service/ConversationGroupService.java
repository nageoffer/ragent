package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationSummaryDO;

import java.util.Date;
import java.util.List;

public interface ConversationGroupService {



    List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit);

    List<ConversationMessageDO> listMessagesBetween(String conversationId, String userId, Date after, Date before);

    long countUserMessages(String conversationId, String userId);

    ConversationSummaryDO findLatestSummary(String conversationId, String userId);

    void upsertSummary(ConversationSummaryDO record);

    ConversationDO findConversation(String conversationId, String userId);
}
