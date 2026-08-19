/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.service.VoicePlaybackService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.VoicePlaybackEventHandler;
import com.nageoffer.ai.ragent.rag.service.handler.VoicePlaybackTaskRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 消息语音播放服务默认实现
 */
@Service
@RequiredArgsConstructor
public class VoicePlaybackServiceImpl implements VoicePlaybackService {

    private static final String ROLE_ASSISTANT = "assistant";

    private final ConversationMessageMapper conversationMessageMapper;
    private final StreamCallbackFactory callbackFactory;
    private final VoicePlaybackTaskRunner taskRunner;
    private final StreamTaskManager taskManager;

    @Override
    public void play(String messageId, SseEmitter emitter) {
        String taskId = IdUtil.getSnowflakeNextIdStr();
        ConversationMessageDO message = loadAssistantMessage(messageId, UserContext.getUserId());
        String text = message.getContent();
        if (StrUtil.isBlank(text)) {
            throw new ClientException("消息内容为空，无法播放");
        }

        VoicePlaybackEventHandler callback = callbackFactory.createVoicePlaybackEventHandler(emitter, taskId);
        taskRunner.run(text, callback);
    }

    @Override
    public void stop(String taskId) {
        taskManager.cancel(taskId);
    }

    /**
     * 定位当前用户的 assistant 消息
     */
    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, ROLE_ASSISTANT)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        if (message == null) {
            throw new ClientException("消息不存在");
        }
        return message;
    }
}
