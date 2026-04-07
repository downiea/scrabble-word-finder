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
    private final ImageCropService imageCropService;

    public VisionResult extractBoardState(MultipartFile imageFile, String imageType, GameConfig gameConfig) {
        return extractBoardState(imageFile, imageType, gameConfig, null, null);
    }

    public VisionResult extractBoardState(MultipartFile imageFile, String imageType, GameConfig gameConfig,
                                           CropRegion boardCropOverride, CropRegion tilesCropOverride) {
        try {
            byte[] rawBytes = imageFile.getBytes();
            String mediaType = imageFile.getContentType() != null ? imageFile.getContentType() : "image/jpeg";

            // Determine effective crops (request overrides > config)
            CropRegion boardCrop = boardCropOverride != null ? boardCropOverride
                    : (gameConfig != null ? gameConfig.getBoardCrop() : null);
            CropRegion tilesCrop = tilesCropOverride != null ? tilesCropOverride
                    : (gameConfig != null ? gameConfig.getTilesCrop() : null);

            // Crop board image if region defined
            byte[] boardBytes = boardCrop != null ? imageCropService.crop(rawBytes, boardCrop) : rawBytes;
            String boardMediaType = boardCrop != null ? "image/png" : mediaType;
            String boardBase64 = Base64.getEncoder().encodeToString(boardBytes);

            boolean layoutKnown = gameConfig != null
                    && gameConfig.getBoardLayout() != null
                    && !gameConfig.getBoardLayout().isEmpty();
            boolean isPhysical = "PHYSICAL".equalsIgnoreCase(imageType);

            // If tilesCrop is defined, tiles will come from a separate call — suppress rack reading in board prompt
            boolean separateTilesCall = tilesCrop != null;
            String prompt = layoutKnown
                    ? buildLettersOnlyPrompt(gameConfig, separateTilesCall)
                    : (isPhysical ? buildPhysicalPrompt(gameConfig, separateTilesCall)
                                  : buildDigitalPrompt(gameConfig, separateTilesCall));

            Map<String, Object> requestBody = buildRequestBody(boardBase64, boardMediaType, prompt);
            String boardResponse = callClaude(requestBody);
            VisionResult result = parseResponse(boardResponse, gameConfig);

            // Separate tiles call if tilesCrop is set
            if (separateTilesCall) {
                byte[] tilesBytes = imageCropService.crop(rawBytes, tilesCrop);
                String tilesBase64 = Base64.getEncoder().encodeToString(tilesBytes);
                String extractedTiles = extractTilesFromImage(tilesBase64);
                result.setExtractedTiles(extractedTiles);
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to extract board state from image", e);
            throw new RuntimeException("Board image analysis failed: " + e.getMessage(), e);
        }
    }

    private String extractTilesFromImage(String base64Image) {
        String prompt = """
                These are a player's Scrabble tiles. Read them left to right.
                Return ONLY valid JSON with no other text: {"tiles": "AEINRST"}
                Use uppercase letters A-Z. Use _ for a blank tile.
                If you cannot read the tiles, return: {"tiles": null}
                """;

        Map<String, Object> requestBody = Map.of(
                "model", MODEL,
                "max_tokens", 256,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64", "media_type", "image/png", "data", base64Image)),
                                Map.of("type", "text", "text", prompt)
                        )
                ))
        );

        try {
            String responseBody = callClaude(requestBody);
            JsonNode root = objectMapper.readTree(responseBody);
            String content = findTextBlock(root);
            if (content == null) return null;
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            if (!content.startsWith("{")) {
                int s = content.indexOf('{'), e = content.lastIndexOf('}');
                if (s != -1 && e > s) content = content.substring(s, e + 1);
                else return null;
            }
            JsonNode parsed = objectMapper.readTree(content);
            JsonNode tilesNode = parsed.path("tiles");
            if (tilesNode.isNull() || tilesNode.isMissingNode()) return null;
            String raw = tilesNode.asText().toUpperCase().replaceAll("[^A-Z_]", "");
            return (!raw.isBlank() && raw.length() <= 7) ? raw : null;
        } catch (Exception e) {
            log.warn("Failed to extract tiles from tiles crop", e);
            return null;
        }
    }

    private String buildLettersOnlyPrompt(GameConfig config, boolean suppressRack) {
        String imageContext = config != null ? "This is a " + config.getName() + " game screenshot." : "This is a Scrabble-style game screenshot.";
        String rackInstruction = suppressRack
                ? "The player's rack tiles are NOT in this image — do not look for them. Set extractedTiles to null."
                : "Also read the player's rack tiles if visible (typically shown below or beside the board).";
        return """
                %s

                The multiplier square positions are already known — you do NOT need to detect them.
                Focus only on reading the letters that have been played on the board.

                IMPORTANT — coordinate system:
                - The board grid itself is the only reference. Ignore all surrounding UI (score panels,
                  headers, footers, player names, rack area) — these are NOT part of the grid.
                - The board is a 15x15 grid. Row 0 is the FIRST row of the board grid (topmost board row),
                  column 0 is the FIRST column of the board grid (leftmost board column).
                - If the board image shows row/column labels (numbers or letters along the edges),
                  use those as your ground truth: row label 1 = row 0, label 2 = row 1, etc.
                  Column label A = col 0, B = col 1, etc.
                - Do NOT count any UI elements above or beside the board as rows or columns.

                %s

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
                - warnings: only include if a cell is physically obscured or cut off.
                """.formatted(imageContext, rackInstruction);
    }

    private String buildDigitalPrompt(GameConfig config, boolean suppressRack) {
        String multiplierContext = config != null && config.getMultiplierHint() != null
                ? config.getMultiplierHint()
                : "Read multiplier squares directly from the image — they may use any notation (2L, 3L, 2W, 3W, DL, TL, DW, TW, colour coding, etc.)";
        String rackInstruction = suppressRack
                ? "The player's rack tiles are NOT in this image — set extractedTiles to null."
                : "Also read the player's rack tiles if visible (typically shown below or beside the board).";

        return """
                Analyse this screenshot from a digital Scrabble-style game.

                Game type hint: %s

                Extract:
                1. BOARD STATE — the grid of played tiles AND the multiplier type of every square.
                   Row 0 is the top row, column 0 is the leftmost column.
                   Ignore all UI chrome, menus, score panels outside the board grid.
                2. SQUARE TYPES — read from the image (STANDARD, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD).
                3. %s

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
                - Include ALL 225 cells.
                - letter: single uppercase A-Z or null. Blank playing as letter: lowercase.
                - squareType: STANDARD, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, or TRIPLE_WORD.
                - extractedTiles: 1-7 uppercase letters/underscores, or null.
                """.formatted(multiplierContext, rackInstruction);
    }

    private String buildPhysicalPrompt(GameConfig config, boolean suppressRack) {
        String multiplierContext = config != null && config.getMultiplierHint() != null
                ? config.getMultiplierHint()
                : "Read multiplier squares from the board — standard Scrabble uses DL, TL, DW, TW labels";
        String rackInstruction = suppressRack
                ? "The player's rack tiles are NOT in this image — set extractedTiles to null."
                : "Also read the player's physical tile rack if visible. Read left to right.";

        return """
                Analyse this photograph of a physical Scrabble-style board.

                Game type hint: %s

                The image may have perspective distortion or shadows. Focus on the board grid only.

                Extract:
                1. BOARD STATE — grid of played tiles AND multiplier type of every square.
                2. SQUARE TYPES from board markings (STANDARD, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD).
                3. %s

                Return ONLY valid JSON:
                {
                  "cells": [
                    { "row": 0, "col": 0, "letter": "A", "squareType": "STANDARD" }
                  ],
                  "extractedTiles": "AEINRST",
                  "warnings": []
                }

                Rules: include ALL 225 cells. Letter uppercase or null. Blank=lowercase. squareType required.
                """.formatted(multiplierContext, rackInstruction);
    }

    private Map<String, Object> buildRequestBody(String base64Image, String mediaType, String prompt) {
        return Map.of(
                "model", MODEL,
                "max_tokens", 16000,
                "thinking", Map.of("type", "enabled", "budget_tokens", 5000),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64", "media_type", mediaType, "data", base64Image)),
                                Map.of("type", "text", "text", prompt)
                        )
                ))
        );
    }

    private String callClaude(Map<String, Object> requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        return restTemplate.postForEntity(CLAUDE_API_URL, request, String.class).getBody();
    }

    private String findTextBlock(JsonNode root) {
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        return null;
    }

    private VisionResult parseResponse(String responseBody, GameConfig gameConfig) throws Exception {
        int rowOffset = gameConfig != null ? gameConfig.getRowOffset() : 0;
        int colOffset = gameConfig != null ? gameConfig.getColOffset() : 0;

        JsonNode root = objectMapper.readTree(responseBody);
        String content = findTextBlock(root);
        if (content == null) {
            log.error("No text block in Claude response: {}", responseBody);
            throw new RuntimeException("Claude returned no text content");
        }
        log.debug("Claude raw response: {}", content.length() > 500 ? content.substring(0, 500) + "..." : content);

        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (!content.startsWith("{")) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start) {
                content = content.substring(start, end + 1);
            } else {
                log.error("Claude did not return JSON. Response: {}", content);
                throw new RuntimeException("Claude did not return a valid JSON board analysis. Response: "
                        + content.substring(0, Math.min(200, content.length())));
            }
        }

        JsonNode parsed = objectMapper.readTree(content);
        BoardState boardState = new BoardState();

        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                boardState.setCell(Cell.builder().row(r).col(c).squareType(SquareType.STANDARD).build());
            }
        }

        for (JsonNode cellNode : parsed.path("cells")) {
            int row = cellNode.path("row").asInt() + rowOffset;
            int col = cellNode.path("col").asInt() + colOffset;
            if (row < 0 || row >= BoardState.SIZE || col < 0 || col >= BoardState.SIZE) continue;

            Cell cell = boardState.getCell(row, col);

            JsonNode sqNode = cellNode.path("squareType");
            if (!sqNode.isMissingNode() && !sqNode.isNull()) {
                try {
                    cell.setSquareType(SquareType.valueOf(sqNode.asText()));
                } catch (IllegalArgumentException e) {
                    cell.setSquareType(SquareType.STANDARD);
                }
            }

            JsonNode letterNode = cellNode.path("letter");
            if (!letterNode.isNull() && !letterNode.isMissingNode()) {
                String letterStr = letterNode.asText();
                if (!letterStr.isBlank()) {
                    cell.setLetter(letterStr.toUpperCase().charAt(0));
                }
            }
        }

        String extractedTiles = null;
        JsonNode tilesNode = parsed.path("extractedTiles");
        if (!tilesNode.isNull() && !tilesNode.isMissingNode()) {
            String raw = tilesNode.asText().toUpperCase().replaceAll("[^A-Z_]", "");
            if (!raw.isBlank() && raw.length() <= 7) extractedTiles = raw;
        }

        List<String> warnings = new ArrayList<>();
        for (JsonNode w : parsed.path("warnings")) warnings.add(w.asText());

        return VisionResult.builder()
                .boardState(boardState)
                .extractedTiles(extractedTiles)
                .warnings(warnings)
                .build();
    }
}
