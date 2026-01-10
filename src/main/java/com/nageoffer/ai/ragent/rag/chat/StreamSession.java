package com.nageoffer.ai.ragent.rag.chat;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class StreamSession {

    private final StreamCallback delegate;
    private final AtomicBoolean hasContent = new AtomicBoolean(false);
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    @Getter
    @Setter
    private volatile StreamHandle handle;

    private StreamSession(StreamCallback delegate) {
        this.delegate = delegate;
    }

    public static StreamSession create(StreamCallback delegate) {
        return new StreamSession(delegate);
    }

    public StreamCallback callback() {
        return new StreamCallback() {
            @Override
            public void onContent(String content) {
                hasContent.set(true);
                delegate.onContent(content);
            }

            @Override
            public void onComplete() {
                delegate.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                StreamSession.this.error.set(error);
            }
        };
    }

    public boolean hasContent() {
        return hasContent.get();
    }

    public boolean hasError() {
        return error.get() != null;
    }

    public Throwable getError() {
        return error.get();
    }

    public void forwardError() {
        Throwable ex = error.get();
        if (ex != null) {
            delegate.onError(ex);
        }
    }
}
