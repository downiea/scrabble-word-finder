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

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiVisionProvider implements VisionProvider {

    private static final String MODEL       = "gemini-flash-latest";
    private static final String API_BASE    = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int    MAX_TOKENS  = 2048;

    @Value("${gemini.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override public String  getName()           { return "gemini"; }
    @Override public boolean supportsMultiImage() { return true; }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Single-image call — full quality, used for non-batch paths. */
    @Override
    public String callVision(byte[] imageBytes, String mediaType, String prompt) throws Exception {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        parts.add(inlinePart(imageBytes, mediaType));
        return post(parts, MAX_TOKENS);
    }

    /** Lightweight single-image call (rack tiles, etc.). */
    @Override
    public String callVisionSimple(byte[] imageBytes, String mediaType, String prompt) throws Exception {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        parts.add(inlinePart(imageBytes, mediaType));
        return post(parts, 256);
    }

    /**
     * Multi-image call — all 225 cell images in one request.
     * Images must be in reading order: cell 0 = A1, cell 14 = O1, cell 15 = A2, …, cell 224 = O15.
     */
    @Override
    public String callVisionBatch(List<byte[]> images, String mediaType, String prompt) throws Exception {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        for (byte[] img : images) {
            parts.add(inlinePart(img, mediaType));
        }
        log.info("Gemini batch: sending {} images", images.size());
        return post(parts, MAX_TOKENS);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private String post(List<Map<String, Object>> parts, int maxOutputTokens) throws Exception {
        String url = API_BASE + MODEL + ":generateContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "maxOutputTokens", maxOutputTokens
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String responseBody = restTemplate
                .postForEntity(url, new HttpEntity<>(body, headers), String.class)
                .getBody();

        JsonNode root = objectMapper.readTree(responseBody);

        // Check for API errors
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            String msg = error.path("message").asText("unknown error");
            log.error("Gemini API error: {}", responseBody);
            throw new RuntimeException("Gemini API error: " + msg);
        }

        JsonNode text = root.path("candidates").path(0)
                .path("content").path("parts").path(0).path("text");
        if (text.isMissingNode()) {
            log.error("No text in Gemini response: {}", responseBody);
            throw new RuntimeException("Gemini returned no text content");
        }
        return text.asText();
    }

    private static Map<String, Object> inlinePart(byte[] bytes, String mediaType) {
        return Map.of("inline_data", Map.of(
                "mime_type", mediaType,
                "data", Base64.getEncoder().encodeToString(bytes)
        ));
    }
}
