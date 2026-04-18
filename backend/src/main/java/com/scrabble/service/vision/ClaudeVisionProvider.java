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

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeVisionProvider implements VisionProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-opus-4-6";

    @Value("${anthropic.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "claude"; }

    @Override
    public boolean isAvailable() { return apiKey != null && !apiKey.isBlank() && !apiKey.equals("test-not-used"); }

    @Override
    public String callVision(byte[] imageBytes, String mediaType, String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "max_tokens", 16000,
                "thinking", Map.of("type", "enabled", "budget_tokens", 8000),
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        imageBlock(imageBytes, mediaType),
                        Map.of("type", "text", "text", prompt)
                )))
        );
        return post(body);
    }

    @Override
    public String callVisionSimple(byte[] imageBytes, String mediaType, String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "max_tokens", 256,
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        imageBlock(imageBytes, mediaType),
                        Map.of("type", "text", "text", prompt)
                )))
        );
        return post(body);
    }

    /** Finds the first text block in a Claude response (skips thinking blocks). */
    public static String findTextBlock(JsonNode root) {
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        return null;
    }

    private String post(Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        String responseBody = restTemplate.postForEntity(API_URL, new HttpEntity<>(body, headers), String.class).getBody();
        JsonNode root = objectMapper.readTree(responseBody);
        String text = findTextBlock(root);
        if (text == null) {
            log.error("No text block in Claude response: {}", responseBody);
            throw new RuntimeException("Claude returned no text content");
        }
        return text;
    }

    private static Map<String, Object> imageBlock(byte[] bytes, String mediaType) {
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        return Map.of("type", "image", "source",
                Map.of("type", "base64", "media_type", mediaType, "data", base64));
    }
}
