package com.nageoffer.ai.ragent.rag.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.config.AIModelProperties;
import com.nageoffer.ai.ragent.convention.ChatMessage;
import com.nageoffer.ai.ragent.convention.ChatRequest;
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
public class OllamaChatClient implements ChatClient {

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = provider.getUrl() + "/api/chat";

        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("stream", false);

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

        Request requestHttp = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        JsonObject json;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("Ollama chat 请求失败: status={}, body={}", response.code(), errBody);
                throw new IllegalStateException("Ollama chat 请求失败: HTTP " + response.code());
            }
            json = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Ollama chat 请求失败: " + e.getMessage(), e);
        }

        return json
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
                throw new IllegalStateException("Ollama 流式请求失败: HTTP " + response.code() + " - " + body);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("Ollama 流式响应为空");
            }
            BufferedSource source = body.source();
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }

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
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = provider.getUrl() + "/api/chat";

        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
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

        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        if (request.getSystemPrompt() != null &&
                !request.getSystemPrompt().isEmpty()) {
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
                msg.addProperty("role", toOllamaRole(m.getRole()));
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

    private String toOllamaRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null || target.provider().getUrl() == null) {
            throw new IllegalStateException("Ollama provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("Ollama model name is missing");
        }
        return target.candidate().getModel();
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new IllegalStateException("Ollama 响应为空");
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
}
