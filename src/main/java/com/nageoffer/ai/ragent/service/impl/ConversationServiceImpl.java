package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.config.MemoryProperties;
import com.nageoffer.ai.ragent.controller.request.ConversationCreateRequest;
import com.nageoffer.ai.ragent.controller.request.ConversationUpdateRequest;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import com.nageoffer.ai.ragent.rag.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.constant.RAGConstant.CONVERSATION_TITLE_PROMPT_PATH;

/**
 * 会话服务实现类
 * 处理会话的创建、更新、重命名和删除等业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final MemoryProperties memoryProperties;
    private final PromptTemplateLoader promptTemplateLoader;
    private final LLMService llmService;

    @Override
    public void createOrUpdate(ConversationCreateRequest request) {
        String userId = request.getUserId();
        String conversationId = request.getConversationId();
        String question = request.getQuestion();
        if (StrUtil.isBlank(userId)) {
            throw new ClientException("用户信息缺失");
        }

        ConversationDO existing = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );

        if (existing == null) {
            String title = generateTitleFromQuestion(question);
            ConversationDO record = ConversationDO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .title(title)
                    .lastTime(request.getLastTime())
                    .build();
            conversationMapper.insert(record);
            return;
        }

        existing.setLastTime(request.getLastTime());
        conversationMapper.updateById(existing);
    }

    @Override
    public void rename(String conversationId, String userId, ConversationUpdateRequest request) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            throw new ClientException("会话信息缺失");
        }

        String title = request == null ? null : request.getTitle();
        if (StrUtil.isBlank(title)) {
            throw new ClientException("会话名称不能为空");
        }

        ConversationDO record = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (record == null) {
            throw new ClientException("会话不存在");
        }
        if (!record.getUserId().equals(userId)) {
            throw new ClientException("会话不属于当前用户");
        }

        record.setTitle(title.trim());
        conversationMapper.updateById(record);
    }

    @Override
    public void delete(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            throw new ClientException("会话信息缺失");
        }

        ConversationDO record = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (record == null) {
            throw new ClientException("会话不存在");
        }

        conversationMapper.deleteById(record.getId());
        messageMapper.delete(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        summaryMapper.delete(
                Wrappers.lambdaQuery(ConversationSummaryDO.class)
                        .eq(ConversationSummaryDO::getConversationId, conversationId)
                        .eq(ConversationSummaryDO::getUserId, userId)
                        .eq(ConversationSummaryDO::getDeleted, 0)
        );
    }

    private String generateTitleFromQuestion(String question) {
        int maxLen = memoryProperties.getTitleMaxLength();
        if (maxLen <= 0) {
            maxLen = 30;
        }
        String prompt = promptTemplateLoader.render(
                CONVERSATION_TITLE_PROMPT_PATH,
                Map.of(
                        "title_max_chars", String.valueOf(maxLen),
                        "question", question
                )
        );

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.7D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();

            return llmService.chat(request);
        } catch (Exception ex) {
            log.warn("生成会话标题失败", ex);
            return "新对话";
        }
    }
}
