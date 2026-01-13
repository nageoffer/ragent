package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationSummaryDO;

import java.util.Date;
import java.util.List;


/**
 * 对话组服务接口
 * 提供对话消息、摘要和对话信息的查询功能
 */
public interface ConversationGroupService {

    /**
     * 获取指定对话中最新的用户消息列表
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param limit          返回的消息数量限制
     * @return 用户消息列表，按时间倒序排列
     */
    List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit);

    /**
     * 获取指定ID范围内的消息列表
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param afterId        起始消息ID（不包含）
     * @param beforeId       结束消息ID（不包含）
     * @return 指定范围内的消息列表
     */
    List<ConversationMessageDO> listMessagesBetweenIds(String conversationId, String userId, Long afterId, Long beforeId);

    /**
     * 查找指定时间点之前或当时的最大消息ID
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param at             指定的时间点
     * @return 最大消息ID，如果不存在则返回null
     */
    Long findMaxMessageIdAtOrBefore(String conversationId, String userId, Date at);

    /**
     * 统计用户在指定对话中的消息数量
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 用户消息总数
     */
    long countUserMessages(String conversationId, String userId);

    /**
     * 获取指定对话的最新摘要信息
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 最新的对话摘要，如果不存在则返回null
     */
    ConversationSummaryDO findLatestSummary(String conversationId, String userId);

    /**
     * 查找指定的对话信息
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 对话信息，如果不存在则返回null
     */
    ConversationDO findConversation(String conversationId, String userId);
}
