package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationGroupServiceImpl implements ConversationGroupService {

    private final ConversationMessageMapper messageMapper;
    private final ConversationMapper conversationMapper;

    @Override
    public List<ConversationMessageDO> listLatestMessages(String conversationId, String userId, int limit) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }
        return messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getIsSummary, 0)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit " + limit)
        );
    }

    @Override
    public List<ConversationMessageDO> listMessagesAsc(String conversationId, String userId, int limit) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }
        return messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getIsSummary, 0)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByAsc(ConversationMessageDO::getCreateTime)
                        .last("limit " + limit)
        );
    }

    @Override
    public long countMessages(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return 0;
        }
        return messageMapper.selectCount(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getIsSummary, 0)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
    }

    @Override
    public List<ConversationMessageDO> listEarliestMessages(String conversationId, String userId, int limit) {
        return listMessagesAsc(conversationId, userId, limit);
    }

    @Override
    public ConversationMessageDO findLatestSummary(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        List<ConversationMessageDO> summaries = messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getIsSummary, 1)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit 1")
        );
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        return summaries.get(0);
    }

    @Override
    public void saveMessage(ConversationMessageDO record) {
        if (record == null) {
            return;
        }
        messageMapper.insert(record);
    }

    @Override
    public void upsertSummary(ConversationMessageDO record) {
        if (record == null) {
            return;
        }
        if (record.getId() == null) {
            messageMapper.insert(record);
        } else {
            messageMapper.updateById(record);
        }
    }

    @Override
    public ConversationDO findConversation(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
    }

    @Override
    public void upsertConversation(ConversationDO record) {
        if (record == null) {
            return;
        }
        if (record.getId() == null) {
            conversationMapper.insert(record);
        } else {
            conversationMapper.updateById(record);
        }
    }
}
