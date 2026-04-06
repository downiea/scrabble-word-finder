package com.scrabble.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.*;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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

    public VisionResult extractBoardState(MultipartFile imageFile, String imageType, GameConfig gameConfig) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageFile.getBytes());
            String mediaType = imageFile.getContentType() != null ? imageFile.getContentType() : "image/jpeg";

            boolean layoutKnown = gameConfig != null
                    && gameConfig.getBoardLayout() != null
                    && !gameConfig.getBoardLayout().isEmpty();
            boolean isPhysical = "PHYSICAL".equalsIgnoreCase(imageType);
            String prompt = layoutKnown
                    ? buildLettersOnlyPrompt(gameConfig)
                    : (isPhysical ? buildPhysicalPrompt(gameConfig) : buildDigitalPrompt(gameConfig));
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

    private String buildLettersOnlyPrompt(GameConfig config) {
        String imageContext = config != null ? "This is a " + config.getName() + " game screenshot." : "This is a Scrabble-style game screenshot.";
        return """
                %s

                The multiplier square positions are already known — you do NOT need to detect them.
                Focus only on reading the letters that have been played on the board.

                The board is a 15x15 grid. Row 0 is the top row, column 0 is the leftmost column.
                Ignore all UI chrome, menus, score panels, or elements outside the board grid.

                Also read the player's rack tiles if visible (typically shown below or beside the board).

                This is a high-resolution digital screenshot — every tile letter should be clearly legible.
                Commit to your best read for every tile. Only add a warning if a cell is genuinely obscured or cut off.

                Return ONLY valid JSON in this exact format, with no other text:
                {
                  "cells": [
                    { "row": 0, "col": 0, "letter": "A" },
                    { "row": 0, "col": 1, "letter": null }
                  ],
                  "extractedTiles": "AEINRST",
                  "warnings": []
                }

                Rules:
                - Include only cells that have a letter on them, OR include all 225 cells with null for empty.
                - letter must be a single uppercase A-Z character, or null for empty cells.
                - Blank tile playing as a letter: return that letter in lowercase.
                - extractedTiles: 1-7 uppercase letters or underscores, or null if not visible.
                - warnings: only include if a cell is physically obscured or cut off — not just visually similar letters.
                """.formatted(imageContext);
    }

    private String buildDigitalPrompt(GameConfig config) {
        String multiplierContext = config != null && config.getMultiplierHint() != null
                ? config.getMultiplierHint()
                : "Read multiplier squares directly from the image — they may use any notation (2L, 3L, 2W, 3W, DL, TL, DW, TW, colour coding, etc.)";

        return """
                Analyse this screenshot from a digital Scrabble-style game.

                Game type hint: %s

                Extract three things:

                1. BOARD STATE — the grid of played tiles AND the multiplier type of every square.
                   Row 0 is the top row, column 0 is the leftmost column.
                   Ignore all UI chrome, menus, score panels, or elements outside the board grid.

                2. SQUARE TYPES — for every square on the board (occupied or empty), identify the multiplier:
                   - DOUBLE_LETTER  — doubles the letter score (2L, DL, light blue, etc.)
                   - TRIPLE_LETTER  — triples the letter score (3L, TL, dark blue, etc.)
                   - DOUBLE_WORD    — doubles the word score (2W, DW, pink, etc.)
                   - TRIPLE_WORD    — triples the word score (3W, TW, red, etc.)
                   - STANDARD       — no multiplier
                   You MUST read the actual positions from this image — do NOT assume a standard Scrabble layout.

                3. RACK TILES — the player's current letter tiles if visible. Read left to right. Use _ for blank.
                   Return null if not visible or not confident.

                Return ONLY valid JSON in this exact format, with no other text:
                {
                  "cells": [
                    { "row": 0, "col": 0, "letter": "A", "squareType": "STANDARD" },
                    { "row": 0, "col": 1, "letter": null, "squareType": "TRIPLE_WORD" }
                  ],
                  "extractedTiles": "AEINRST",
                  "warnings": ["optional low-confidence reads"]
                }

                Rules:
                - Include ALL 225 cells (all rows 0-14, cols 0-14) — every cell needs a squareType.
                - letter must be a single uppercase A-Z character, or null for empty cells.
                - Blank tile playing as a letter: return that letter in lowercase.
                - squareType must be one of: STANDARD, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD.
                - extractedTiles: 1-7 uppercase letters or underscores, or null.
                """.formatted(multiplierContext);
    }

    private String buildPhysicalPrompt(GameConfig config) {
        String multiplierContext = config != null && config.getMultiplierHint() != null
                ? config.getMultiplierHint()
                : "Read multiplier squares from the board — standard Scrabble uses DL, TL, DW, TW labels and colour coding";

        return """
                Analyse this photograph of a physical Scrabble-style board.

                Game type hint: %s

                The image may have perspective distortion, shadows, or be taken at an angle.
                Focus on the board grid itself — ignore hands, table, boxes, score pads, etc.

                Extract three things:

                1. BOARD STATE — the grid of played tiles AND the multiplier type of every square.
                   Row 0 is the top row, column 0 is the leftmost column.
                   Account for perspective and lighting. Flag ambiguous tiles in warnings.

                2. SQUARE TYPES — for every square, identify the multiplier from the board markings/colours:
                   - DOUBLE_LETTER  (2L, DL, light blue)
                   - TRIPLE_LETTER  (3L, TL, dark blue)
                   - DOUBLE_WORD    (2W, DW, pink/rose)
                   - TRIPLE_WORD    (3W, TW, red/orange)
                   - STANDARD       (no marking)
                   You MUST read the actual positions from this image.

                3. RACK TILES — the player's physical letter tiles if visible. Read left to right. Use _ for blank.
                   Return null if not visible or not confident.

                Return ONLY valid JSON in this exact format, with no other text:
                {
                  "cells": [
                    { "row": 0, "col": 0, "letter": "A", "squareType": "STANDARD" },
                    { "row": 0, "col": 1, "letter": null, "squareType": "TRIPLE_WORD" }
                  ],
                  "extractedTiles": "AEINRST",
                  "warnings": ["optional low-confidence reads"]
                }

                Rules:
                - Include ALL 225 cells (all rows 0-14, cols 0-14).
                - letter must be a single uppercase A-Z character, or null for empty cells.
                - Blank tile playing as a letter: return that letter in lowercase.
                - squareType must be one of: STANDARD, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD.
                - extractedTiles: 1-7 uppercase letters or underscores, or null.
                """.formatted(multiplierContext);
    }

    private Map<String, Object> buildRequestBody(String base64Image, String mediaType, String prompt) {
        return Map.of(
                "model", MODEL,
                "max_tokens", 16000,
                // Extended thinking: gives Claude a reasoning budget before answering,
                // significantly improves accuracy on structured visual grids.
                "thinking", Map.of(
                        "type", "enabled",
                        "budget_tokens", 5000
                ),
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

    private VisionResult parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        // With extended thinking enabled Claude returns multiple content blocks;
        // find the first block with type="text"
        String content = null;
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                content = block.path("text").asText();
                break;
            }
        }
        if (content == null) {
            log.error("No text block in Claude response: {}", responseBody);
            throw new RuntimeException("Claude returned no text content");
        }
        log.debug("Claude raw response: {}", content.length() > 500 ? content.substring(0, 500) + "..." : content);

        // Strip markdown code fences
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        // If Claude returned prose + JSON, extract the first {...} block
        if (!content.startsWith("{")) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start) {
                content = content.substring(start, end + 1);
            } else {
                log.error("Claude did not return JSON. Response: {}", content);
                throw new RuntimeException("Claude did not return a valid JSON board analysis. Response: " + content.substring(0, Math.min(200, content.length())));
            }
        }

        JsonNode parsed = objectMapper.readTree(content);
        BoardState boardState = new BoardState();

        // Initialise all cells as STANDARD — will be overridden by Claude's response
        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                boardState.setCell(Cell.builder().row(r).col(c).squareType(SquareType.STANDARD).build());
            }
        }

        for (JsonNode cellNode : parsed.path("cells")) {
            int row = cellNode.path("row").asInt();
            int col = cellNode.path("col").asInt();
            if (row < 0 || row >= BoardState.SIZE || col < 0 || col >= BoardState.SIZE) continue;

            Cell cell = boardState.getCell(row, col);

            // Square type from image
            JsonNode sqNode = cellNode.path("squareType");
            if (!sqNode.isMissingNode() && !sqNode.isNull()) {
                try {
                    cell.setSquareType(SquareType.valueOf(sqNode.asText()));
                } catch (IllegalArgumentException e) {
                    cell.setSquareType(SquareType.STANDARD);
                }
            }

            // Letter
            JsonNode letterNode = cellNode.path("letter");
            if (!letterNode.isNull() && !letterNode.isMissingNode()) {
                String letterStr = letterNode.asText();
                if (!letterStr.isBlank()) {
                    cell.setLetter(letterStr.toUpperCase().charAt(0));
                }
            }
        }

        // Rack tiles
        String extractedTiles = null;
        JsonNode tilesNode = parsed.path("extractedTiles");
        if (!tilesNode.isNull() && !tilesNode.isMissingNode()) {
            String raw = tilesNode.asText().toUpperCase().replaceAll("[^A-Z_]", "");
            if (!raw.isBlank() && raw.length() <= 7) {
                extractedTiles = raw;
            }
        }

        // Warnings
        List<String> warnings = new ArrayList<>();
        for (JsonNode w : parsed.path("warnings")) {
            warnings.add(w.asText());
        }

        return VisionResult.builder()
                .boardState(boardState)
                .extractedTiles(extractedTiles)
                .warnings(warnings)
                .build();
    }
}
