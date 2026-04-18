package com.scrabble.service.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAIVisionProvider implements VisionProvider {

    private static final String API_URL       = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL_FULL    = "gpt-4o";
    private static final String MODEL_SIMPLE  = "gpt-4o-mini";
    private static final int    MAX_TOKENS_FULL   = 16000;
    private static final int    MAX_TOKENS_SIMPLE = 256;

    @Value("${openai.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "openai"; }

    @Override
    public boolean isAvailable() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public String callVision(byte[] imageBytes, String mediaType, String prompt) throws Exception {
        return post(MODEL_FULL, imageBytes, mediaType, prompt, MAX_TOKENS_FULL);
    }

    @Override
    public String callVisionSimple(byte[] imageBytes, String mediaType, String prompt) throws Exception {
        return post(MODEL_SIMPLE, imageBytes, mediaType, prompt, MAX_TOKENS_SIMPLE);
    }

    private String post(String model, byte[] imageBytes, String mediaType, String prompt, int maxTokens) throws Exception {
        String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "temperature", 0,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl, "detail", "high")),
                                Map.of("type", "text", "text", prompt)
                        )
                ))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String responseBody = restTemplate
                .postForEntity(API_URL, new HttpEntity<>(body, headers), String.class)
                .getBody();

        JsonNode root = objectMapper.readTree(responseBody);

        // Check for API error response
        if (root.has("error")) {
            String error = root.path("error").path("message").asText("Unknown OpenAI error");
            log.error("OpenAI API error: {}", error);
            throw new RuntimeException("OpenAI API error: " + error);
        }

        String text = root.path("choices").path(0).path("message").path("content").asText(null);
        if (text == null || text.isBlank()) {
            log.error("Empty response from OpenAI: {}", responseBody);
            throw new RuntimeException("OpenAI returned empty content");
        }
        return text;
    }
}
