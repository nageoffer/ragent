package com.nageoffer.ai.ragent.framework.web;

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

public class SseEmitterSender {

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public void sendEvent(String eventName, Object data) {
        if (closed.get()) {
            throw new ServiceException("SSE already closed", BaseErrorCode.SERVICE_ERROR);
        }
        try {
            if (eventName == null) {
                emitter.send(data);
                return;
            }
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            fail(e);
        }
    }

    public void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    public void fail(Throwable throwable) {
        closeWithError(throwable);
        throw new ServiceException("SSE send failed", throwable, BaseErrorCode.SERVICE_ERROR);
    }

    private void closeWithError(Throwable throwable) {
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(throwable);
        }
    }
}
