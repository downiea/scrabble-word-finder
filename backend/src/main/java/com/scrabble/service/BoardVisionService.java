package com.scrabble.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.BoardState;
import com.scrabble.model.Cell;
import com.scrabble.model.SquareType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Sends the board image to the Claude Vision API and parses the response
 * into a structured {@link BoardState}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardVisionService {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-4-6";

    @Value("${anthropic.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public BoardState extractBoardState(MultipartFile imageFile) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageFile.getBytes());
            String mediaType = imageFile.getContentType() != null ? imageFile.getContentType() : "image/jpeg";

            String prompt = buildPrompt();
            Map<String, Object> requestBody = buildRequestBody(base64Image, mediaType, prompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(CLAUDE_API_URL, request, String.class);

            return parseResponse(response.getBody());
        } catch (Exception e) {
            log.error("Failed to extract board state from image", e);
            throw new RuntimeException("Board image analysis failed: " + e.getMessage(), e);
        }
    }

    private String buildPrompt() {
        return """
                Analyse this Scrabble board image and return the board state as JSON.

                The board is a 15x15 grid. Row 0 is the top row, column 0 is the leftmost column.

                Return ONLY valid JSON in this exact format, with no other text:
                {
                  "cells": [
                    { "row": 0, "col": 0, "letter": "A" },
                    { "row": 0, "col": 1, "letter": null }
                  ],
                  "warnings": ["optional list of low-confidence tile reads"]
                }

                Rules:
                - Include only cells that have a letter on them (letter != null), OR include all 225 cells.
                - letter must be a single uppercase A-Z character, or null for empty cells.
                - If a tile is a blank tile playing as a letter, still return that letter in lowercase.
                - warnings should flag any tiles you were not confident about.
                """;
    }

    private Map<String, Object> buildRequestBody(String base64Image, String mediaType, String prompt) {
        return Map.of(
                "model", MODEL,
                "max_tokens", 4096,
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of(
                                                "type", "image",
                                                "source", Map.of(
                                                        "type", "base64",
                                                        "media_type", mediaType,
                                                        "data", base64Image
                                                )
                                        ),
                                        Map.of("type", "text", "text", prompt)
                                )
                        )
                )
        );
    }

    private BoardState parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String content = root.path("content").get(0).path("text").asText();

        // Strip markdown code fences if present
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        JsonNode parsed = objectMapper.readTree(content);
        BoardState boardState = new BoardState();

        // Initialise all cells with standard square types
        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                SquareType squareType = BoardState.standardSquareType(r, c);
                boardState.setCell(Cell.builder().row(r).col(c).squareType(squareType).build());
            }
        }

        // Populate letters from Claude's response
        for (JsonNode cellNode : parsed.path("cells")) {
            int row = cellNode.path("row").asInt();
            int col = cellNode.path("col").asInt();
            JsonNode letterNode = cellNode.path("letter");

            if (!letterNode.isNull() && !letterNode.isMissingNode()) {
                String letterStr = letterNode.asText();
                if (!letterStr.isBlank()) {
                    char letter = letterStr.toUpperCase().charAt(0);
                    Cell cell = boardState.getCell(row, col);
                    cell.setLetter(letter);
                }
            }
        }

        return boardState;
    }
}
