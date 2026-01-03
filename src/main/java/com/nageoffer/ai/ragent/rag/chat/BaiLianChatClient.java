package com.nageoffer.ai.ragent.rag.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.config.AIModelProperties;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
import com.nageoffer.ai.ragent.enums.ModelCapability;
import com.nageoffer.ai.ragent.rag.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.rag.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.stereotype.Service;
import com.nageoffer.ai.ragent.rag.http.HttpMediaTypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianChatClient implements ChatClient {

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return "bailian";
    }

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);

        JsonObject reqBody = buildRequestBody(request, target, false);
        Request requestHttp = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("百炼同步请求失败: status={}, body={}", response.code(), body);
                throw new IllegalStateException("百炼同步请求失败: HTTP " + response.code());
            }
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("百炼同步请求失败: " + e.getMessage(), e);
        }

        return respJson
                .getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
    }

    @Override
    public StreamHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Call call = httpClient.newCall(buildStreamRequest(request, target));
        doStream(call, callback, cancelled);
        return () -> {
            cancelled.set(true);
            call.cancel();
        };
    }

    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                throw new IllegalStateException("百炼流式请求失败: HTTP " + response.code() + " - " + body);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("百炼流式响应为空");
            }
            BufferedSource source = body.source();
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

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

                    if (choice0.has("delta") && choice0.get("delta").isJsonObject()) {
                        JsonObject delta = choice0.getAsJsonObject("delta");
                        if (delta.has("content")) {
                            JsonElement ce = delta.get("content");
                            if (ce != null && !ce.isJsonNull()) {
                                chunk = ce.getAsString();
                            }
                        }
                    }

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

                    if (choice0.has("finish_reason")) {
                        JsonElement fr = choice0.get("finish_reason");
                        if (fr != null && !fr.isJsonNull()) {
                            callback.onComplete();
                            break;
                        }
                    }

                } catch (Exception parseEx) {
                    log.warn("百炼流式响应解析失败: payload={}", payload, parseEx);
                }
            }
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", requireModel(target));
        if (stream) {
            reqBody.addProperty("stream", true);
        }

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
        if (stream && request.getThinking() != null && request.getThinking()) {
            reqBody.addProperty("enable_thinking", true);
        }
        return reqBody;
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", request.getSystemPrompt());
            arr.add(sys);
        }

        if (request.getContext() != null && !request.getContext().isEmpty()) {
            JsonObject ctx = new JsonObject();
            ctx.addProperty("role", "system");
            ctx.addProperty("content", "以下是与用户问题相关的背景知识：\n" + request.getContext());
            arr.add(ctx);
        }

        if (request.getHistory() != null) {
            for (ChatMessage m : request.getHistory()) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }

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

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("BaiLian provider config is missing");
        }
        if (target.provider().getApiKey() == null || target.provider().getApiKey().isBlank()) {
            throw new IllegalStateException("BaiLian apiKey is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("BaiLian model name is missing");
        }
        return target.candidate().getModel();
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        JsonObject reqBody = buildRequestBody(request, target, true);
        return new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Accept", "text/event-stream")
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new IllegalStateException("百炼响应为空");
        }
        String content = body.string();
        return gson.fromJson(content, JsonObject.class);
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
    }
}
