package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.service.ConversationMessageService;
import com.nageoffer.ai.ragent.service.bo.ConversationMessageBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl implements ConversationMessageService {

    private final ConversationMessageMapper conversationMessageMapper;

    @Override
    public void addMessage(ConversationMessageBO conversationMessage) {
        ConversationMessageDO messageDO = BeanUtil.toBean(conversationMessage, ConversationMessageDO.class);
        conversationMessageMapper.insert(messageDO);
    }

    @Override
    public List<ConversationMessageDO> listLatestMessages(String conversationId, String userId, Integer limit) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }
        return conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .in(ConversationMessageDO::getRole, "user", "assistant")
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit " + limit)
        );
    }
}
