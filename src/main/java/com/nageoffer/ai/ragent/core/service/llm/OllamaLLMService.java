package com.nageoffer.ai.ragent.core.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama")
public class OllamaLLMService implements LLMService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final Gson gson = new Gson();

    @Value("${ollama.llm-model:qwen3:8b}")
    private String llmModel;

    @Value("${ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Override
    public String chat(String prompt) {
        String url = ollamaUrl + "/api/chat";

        JsonObject body = new JsonObject();
        body.addProperty("model", llmModel);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);

        body.add("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> req = new HttpEntity<>(body.toString(), headers);

        ResponseEntity<String> resp =
                restTemplate.postForEntity(url, req, String.class);

        JsonObject json = gson.fromJson(resp.getBody(), JsonObject.class);

        String answer = json
                .getAsJsonObject("message")
                .get("content")
                .getAsString();

        return answer;
    }
}
