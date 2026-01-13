package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.service.ConversationMessageService;
import com.nageoffer.ai.ragent.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl implements ConversationMessageService {

    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationMapper conversationMapper;


    @Override
    public void addMessage(ConversationMessageBO conversationMessage) {
        ConversationMessageDO messageDO = BeanUtil.toBean(conversationMessage, ConversationMessageDO.class);
        conversationMessageMapper.insert(messageDO);
    }

    @Override
    public List<ConversationMessageVO> listMessages(String conversationId) {
        return listLatestMessages(conversationId, null);
    }

    @Override
    public List<ConversationMessageVO> listLatestMessages(String conversationId, Integer limit) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        ConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }

        List<ConversationMessageDO> records = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByAsc(ConversationMessageDO::getCreateTime)
                        .last(limit != null, "limit " + limit)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<ConversationMessageVO> result = new ArrayList<>();
        for (ConversationMessageDO record : records) {
            ConversationMessageVO vo = ConversationMessageVO.builder()
                    .id(record.getId())
                    .conversationId(record.getConversationId())
                    .role(record.getRole())
                    .content(record.getContent())
                    .createTime(record.getCreateTime())
                    .build();
            result.add(vo);
        }

        return result;
    }

    @Override
    public void addMessageSummary(ConversationSummaryBO conversationSummary) {
        ConversationSummaryDO conversationSummaryDO = BeanUtil.toBean(conversationSummary, ConversationSummaryDO.class)
                .setCreateTime(conversationSummary.getSummaryTime())
                .setUpdateTime(conversationSummary.getSummaryTime());
        conversationSummaryMapper.insert(conversationSummaryDO);
    }
}
