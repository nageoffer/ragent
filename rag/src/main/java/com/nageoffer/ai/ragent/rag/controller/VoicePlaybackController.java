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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.service.VoicePlaybackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 消息语音播放控制器
 */
@RestController
@RequiredArgsConstructor
public class VoicePlaybackController {

    private final VoicePlaybackService voicePlaybackService;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 发起消息语音播放
     */
    @GetMapping(value = "/rag/v3/voice/play", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter play(@RequestParam String messageId) {
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        voicePlaybackService.play(messageId, emitter);
        return emitter;
    }

    /**
     * 停止指定播放任务
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/voice/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        voicePlaybackService.stop(taskId);
        return Results.success();
    }
}
