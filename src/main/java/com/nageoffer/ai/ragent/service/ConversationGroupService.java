package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationSummaryDO;

import java.util.List;

public interface ConversationGroupService {

    List<ConversationMessageDO> listLatestUserMessages(String conversationId, String userId, int limit);

    List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit);

    List<ConversationMessageDO> listMessagesBetween(String conversationId,
                                                    String userId,
                                                    java.util.Date after,
                                                    java.util.Date before);

    long countUserMessages(String conversationId, String userId);

    ConversationSummaryDO findLatestSummary(String conversationId, String userId);

    void saveMessage(ConversationMessageDO record);

    void upsertSummary(ConversationSummaryDO record);

    ConversationDO findConversation(String conversationId, String userId);

    void upsertConversation(ConversationDO record);
}
