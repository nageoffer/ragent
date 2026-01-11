package com.nageoffer.ai.ragent.service.handler;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.dto.MessageDelta;
import com.nageoffer.ai.ragent.dto.MetaPayload;
import com.nageoffer.ai.ragent.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.memory.ConversationMemoryService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class StreamChatEventHandler implements StreamCallback {

    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final StringBuilder answer = new StringBuilder();

    public StreamChatEventHandler(SseEmitter emitter,
                                  String conversationId,
                                  String taskId,
                                  ConversationMemoryService memoryService,
                                  StreamTaskManager taskManager) {
        this.sender = new SseEmitterSender(emitter);
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.memoryService = memoryService;
        this.taskManager = taskManager;
        this.userId = UserContext.getUserId();
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        // 注册时传入取消回调，用于在取消时保存已累积的回复
        taskManager.register(taskId, sender, this::saveAnswerIfNotEmpty);
    }

    /**
     * 保存已累积的回复内容（如果不为空）
     */
    private void saveAnswerIfNotEmpty() {
        String content = answer.toString();
        if (StrUtil.isNotBlank(content)) {
            memoryService.append(conversationId, userId, ChatMessage.assistant(content));
        }
    }

    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        answer.append(chunk);
        int[] codePoints = chunk.codePoints().toArray();
        for (int codePoint : codePoints) {
            String character = new String(new int[]{codePoint}, 0, 1);
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(character));
        }
    }

    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        memoryService.append(conversationId, UserContext.getUserId(),
                ChatMessage.assistant(answer.toString()));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }
}
