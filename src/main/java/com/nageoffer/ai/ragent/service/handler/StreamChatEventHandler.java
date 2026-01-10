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
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, emitter);
    }

    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            sender.complete();
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
            taskManager.unregister(taskId);
            sender.complete();
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
            sender.complete();
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }
}
