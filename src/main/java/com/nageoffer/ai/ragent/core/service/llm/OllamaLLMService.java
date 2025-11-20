package com.nageoffer.ai.ragent.core.service.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.config.ChatProperties;
import com.nageoffer.ai.ragent.core.convention.ChatMessage;
import com.nageoffer.ai.ragent.core.convention.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.chat.provider", havingValue = "ollama")
public class OllamaLLMService implements LLMService {

    private final ChatProperties chatProperties;

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String chat(ChatRequest request) {
        ChatProperties.ChatOllamaProperties properties = chatProperties.getOllama();
        String url = properties.url() + "/api/chat";

        JsonObject body = new JsonObject();
        body.addProperty("model", properties.model());
        body.addProperty("stream", false);

        // 构造 messages（system + RAG context + history + user）
        JsonArray messages = buildMessages(request);
        body.add("messages", messages);

        // 可选参数：temperature、top_p、num_predict（maxTokens）等
        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("num_predict", request.getMaxTokens());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> req = new HttpEntity<>(body.toString(), headers);

        ResponseEntity<String> resp =
                restTemplate.postForEntity(url, req, String.class);

        JsonObject json = gson.fromJson(resp.getBody(), JsonObject.class);

        return json
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
    }

    @Override
    public StreamHandle streamChat(ChatRequest request, StreamCallback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // 直接在当前线程执行，直到流结束
        doStream(request, callback, cancelled);
        // 如果你暂时不需要 cancel，其实返回值都可以先不用
        return () -> cancelled.set(true);
    }

    private void doStream(ChatRequest request, StreamCallback callback, AtomicBoolean cancelled) {
        HttpURLConnection conn = null;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", chatProperties.getOllama().model());
            body.addProperty("stream", true);

            JsonArray messages = buildMessages(request);
            body.add("messages", messages);

            if (request.getTemperature() != null) {
                body.addProperty("temperature", request.getTemperature());
            }
            if (request.getTopP() != null) {
                body.addProperty("top_p", request.getTopP());
            }
            if (request.getMaxTokens() != null) {
                body.addProperty("num_predict", request.getMaxTokens());
            }

            URL url = new URL(chatProperties.getOllama().url() + "/api/chat");
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);

            String jsonBody = gson.toJson(body);
            conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while (!cancelled.get() && (line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;

                    JsonObject obj = gson.fromJson(line, JsonObject.class);

                    if (obj.has("done") && obj.get("done").getAsBoolean()) {
                        callback.onComplete();
                        break;
                    }

                    if (obj.has("message")) {
                        JsonObject msg = obj.getAsJsonObject("message");
                        if (msg.has("content")) {
                            String chunk = msg.get("content").getAsString();
                            if (!chunk.isEmpty()) {
                                callback.onContent(chunk);
                            }
                        }
                    }
                }

                // 如果是外部 cancel 触发，可以选择在这里调用 onComplete()
                if (cancelled.get()) {
                    // 视业务需求决定要不要 onComplete
                }
            }
        } catch (Exception e) {
            callback.onError(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        // systemPrompt
        if (request.getSystemPrompt() != null &&
                !request.getSystemPrompt().isEmpty()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", request.getSystemPrompt());
            arr.add(sys);
        }

        // RAG 上下文（你可以选择拼到 system 里，也可以当成单独一条 system）
        if (request.getContext() != null && !request.getContext().isEmpty()) {
            JsonObject ctx = new JsonObject();
            ctx.addProperty("role", "system");
            ctx.addProperty("content", "以下是与用户问题相关的背景知识：\n" + request.getContext());
            arr.add(ctx);
        }

        // 历史对话
        if (request.getHistory() != null) {
            for (ChatMessage m : request.getHistory()) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOllamaRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }

        // 当前用户输入
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", request.getPrompt());
        arr.add(userMsg);

        return arr;
    }

    private String toOllamaRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }
}


