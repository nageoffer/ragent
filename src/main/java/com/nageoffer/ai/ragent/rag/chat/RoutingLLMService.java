package com.nageoffer.ai.ragent.rag.chat;

import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.enums.ModelCapability;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.rag.model.ModelHealthStore;
import com.nageoffer.ai.ragent.rag.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.rag.model.ModelSelector;
import com.nageoffer.ai.ragent.rag.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    public String chat(ChatRequest request) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates();
        if (targets.isEmpty()) {
            throw new RemoteException("没有可用的Chat模型候选者");
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean hasContent = new AtomicBoolean(false);
            AtomicReference<Throwable> error = new AtomicReference<>();

            StreamCallback wrapper = new StreamCallback() {
                @Override
                public void onContent(String content) {
                    hasContent.set(true);
                    latch.countDown();
                    callback.onContent(content);
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                    callback.onComplete();
                }

                @Override
                public void onError(Throwable t) {
                    error.set(t);
                    latch.countDown();
                }
            };

            StreamCancellationHandle handle = client.streamChat(request, wrapper, target);

            boolean completed;
            try {
                completed = latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handle.cancel();
                throw new RemoteException("流式请求被中断", e, BaseErrorCode.REMOTE_ERROR);
            }

            if (hasContent.get()) {
                healthStore.markSuccess(target.id());
                return handle;
            }

            if (error.get() != null) {
                lastError = error.get();
                healthStore.markFailure(target.id());
                handle.cancel();
                log.warn("{} 流式请求失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), lastError);
                continue;
            }

            // 超时未收到内容也视为成功启动（模型响应较慢但已建立连接）
            if (!completed) {
                log.debug("{} 流式请求超时未收到首内容，继续等待。modelId：{}", label, target.id());
            }
            healthStore.markSuccess(target.id());
            return handle;
        }

        throw new RemoteException(
                "所有Chat模型候选者都失败了: " + (lastError == null ? "未知" : lastError.getMessage()),
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
    }

    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }
}
