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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.validation.ChatQuestion;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 对话入口，仅 ragent.engine.type=agent 时注册；RAG v3 接口不受影响
 */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentProperties agentProperties;

    @GetMapping(value = "/agent/v1/chat", produces = "text/event-stream;charset=UTF-8") // SSE（Server‑Sent‑Events） 流式响应,基于 HTTP，服务端→客户端单向流式；长连接。
    public SseEmitter chat(@RequestParam @ChatQuestion String question,
                           @RequestParam(required = false) String conversationId) {
        SseEmitter emitter = new SseEmitter(agentProperties.getSseTimeoutMs());  //question->agent:1. 新建一个 SseEmitter 对象，用于向客户端发送事件流。超时时间由配置文件中的 sseTimeoutMs 属性指定。
        agentChatService.streamChat(question, conversationId, emitter);
        return emitter;
    }

    @PostMapping("/agent/v1/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        agentChatService.stopTask(taskId);
        return Results.success();
    }
}
