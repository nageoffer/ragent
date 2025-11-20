package com.nageoffer.ai.ragent.core.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
@ConditionalOnProperty(name = "ai.chat.provider", havingValue = "bailian")
public class BaiLianLLMService implements LLMService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final Gson gson = new Gson();

    private final ChatProperties chatProperties;

    @Override
    public String chat(ChatRequest request) {
        ChatProperties.ChatBaiLianProperties properties = chatProperties.getBailian();

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", properties.model());

        // 构造 messages（system + context + history + user）
        JsonArray messages = buildMessages(request);
        reqBody.add("messages", messages);

        // 可选参数
        if (request.getTemperature() != null) {
            reqBody.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            reqBody.addProperty("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            reqBody.addProperty("max_tokens", request.getMaxTokens());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.apiKey());

        HttpEntity<String> httpEntity = new HttpEntity<>(reqBody.toString(), headers);
        ResponseEntity<String> response =
                restTemplate.postForEntity(properties.url(), httpEntity, String.class);

        JsonObject respJson = gson.fromJson(response.getBody(), JsonObject.class);

        return respJson
                .getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
    }

    @Override
    public StreamHandle streamChat(ChatRequest request, StreamCallback callback) {
        // 和 Ollama 一样，先做个简单的同步实现（在当前线程阻塞直到流结束）
        AtomicBoolean cancelled = new AtomicBoolean(false);
        doStream(request, callback, cancelled);
        return () -> cancelled.set(true);
    }

    /**
     * 实际流式逻辑：基于 HttpURLConnection 一行一行读百炼的流
     */
    private void doStream(ChatRequest request, StreamCallback callback, AtomicBoolean cancelled) {
        ChatProperties.ChatBaiLianProperties properties = chatProperties.getBailian();

        HttpURLConnection conn = null;
        try {
            // 1. 构造请求体
            JsonObject reqBody = new JsonObject();
            reqBody.addProperty("model", properties.model());
            reqBody.addProperty("stream", true);

            JsonArray messages = buildMessages(request);
            reqBody.add("messages", messages);

            if (request.getTemperature() != null) {
                reqBody.addProperty("temperature", request.getTemperature());
            }
            if (request.getTopP() != null) {
                reqBody.addProperty("top_p", request.getTopP());
            }
            if (request.getMaxTokens() != null) {
                reqBody.addProperty("max_tokens", request.getMaxTokens());
            }
            if (request.getThinking() != null && request.getThinking()) {
                reqBody.addProperty("enable_thinking", true);
            }

            // 2. 打开连接
            URL url = new URL(properties.url());
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Authorization", "Bearer " + properties.apiKey());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);

            // 3. 发送请求
            String jsonBody = gson.toJson(reqBody);
            conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();

            // 4. 按行读取流式响应
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while (!cancelled.get() && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }

                    // 兼容 "data: {...}" 形式
                    String payload = line.trim();
                    if (payload.startsWith("data:")) {
                        payload = payload.substring("data:".length()).trim();
                    }

                    if ("[DONE]".equalsIgnoreCase(payload)) {
                        callback.onComplete();
                        break;
                    }

                    try {
                        JsonObject obj = gson.fromJson(payload, JsonObject.class);
                        JsonArray choices = obj.getAsJsonArray("choices");
                        if (choices == null || choices.isEmpty()) {
                            continue;
                        }

                        JsonObject choice0 = choices.get(0).getAsJsonObject();
                        String chunk = null;

                        // 优先走 delta 流式
                        if (choice0.has("delta") && choice0.get("delta").isJsonObject()) {
                            JsonObject delta = choice0.getAsJsonObject("delta");
                            if (delta.has("content")) {
                                JsonElement ce = delta.get("content");
                                if (ce != null && !ce.isJsonNull()) {
                                    chunk = ce.getAsString();
                                }
                            }
                        }

                        // 有些实现会在最后一包用 message 传完整内容，这里也兼容一下
                        if (chunk == null && choice0.has("message") && choice0.get("message").isJsonObject()) {
                            JsonObject msg = choice0.getAsJsonObject("message");
                            if (msg.has("content")) {
                                JsonElement ce = msg.get("content");
                                if (ce != null && !ce.isJsonNull()) {
                                    chunk = ce.getAsString();
                                }
                            }
                        }

                        if (chunk != null && !chunk.isEmpty()) {
                            callback.onContent(chunk);
                        }

                        // 处理 finish_reason：非 null 即表示结束
                        if (choice0.has("finish_reason")) {
                            JsonElement fr = choice0.get("finish_reason");
                            if (fr != null && !fr.isJsonNull()) {
                                // 一般是 "stop"
                                callback.onComplete();
                                break;
                            }
                        }

                    } catch (Exception parseEx) {
                        // 建议调试阶段打印一下 payload，方便排查
                        System.err.println("Failed to parse streaming payload: " + payload);
                        parseEx.printStackTrace();
                    }
                }

                if (cancelled.get()) {
                    // 需要的话可以在这里 callback.onComplete();
                }
            }

        } catch (Exception e) {
            callback.onError(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 构造 OpenAI 兼容的 messages 数组
     */
    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        // systemPrompt
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", request.getSystemPrompt());
            arr.add(sys);
        }

        // RAG context
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
                msg.addProperty("role", toOpenAiRole(m.getRole()));
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

    private String toOpenAiRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }
}


