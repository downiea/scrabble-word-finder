package com.scrabble.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.*;
import com.scrabble.service.vision.VisionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BoardVisionService {

    @Value("${vision.provider:claude}")
    private String defaultProviderName;

    private final Map<String, VisionProvider> providers;
    private final ObjectMapper objectMapper;
    private final ImageCropService imageCropService;

    public BoardVisionService(List<VisionProvider> providerList,
                              ObjectMapper objectMapper,
                              ImageCropService imageCropService) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(VisionProvider::getName, Function.identity()));
        this.objectMapper = objectMapper;
        this.imageCropService = imageCropService;
        log.info("Vision providers registered: {}", this.providers.keySet());
    }

    public VisionResult extractBoardState(MultipartFile imageFile, String imageType, GameConfig gameConfig) {
        return extractBoardState(imageFile, imageType, gameConfig, null, null);
    }

    public VisionResult extractBoardState(MultipartFile imageFile, String imageType, GameConfig gameConfig,
                                           CropRegion boardCropOverride, CropRegion tilesCropOverride) {
        try {
            byte[] rawBytes = imageFile.getBytes();
            String mediaType = imageFile.getContentType() != null ? imageFile.getContentType() : "image/jpeg";

            CropRegion boardCrop = boardCropOverride != null ? boardCropOverride
                    : (gameConfig != null ? gameConfig.getBoardCrop() : null);
            CropRegion tilesCrop = tilesCropOverride != null ? tilesCropOverride
                    : (gameConfig != null ? gameConfig.getTilesCrop() : null);

            byte[] boardBytes   = boardCrop != null ? imageCropService.crop(rawBytes, boardCrop) : rawBytes;
            String boardMedia   = boardCrop != null ? "image/png" : mediaType;

            boolean layoutKnown    = gameConfig != null && gameConfig.getBoardLayout() != null && !gameConfig.getBoardLayout().isEmpty();
            boolean isPhysical     = "PHYSICAL".equalsIgnoreCase(imageType);
            boolean separateTiles  = tilesCrop != null;

            String prompt = layoutKnown
                    ? buildLettersOnlyPrompt(gameConfig, separateTiles)
                    : (isPhysical ? buildPhysicalPrompt(gameConfig, separateTiles)
                                  : buildDigitalPrompt(gameConfig, separateTiles));

            VisionProvider provider = resolveProvider(gameConfig);
            log.info("Using vision provider: {} for gameConfig: {}", provider.getName(),
                    gameConfig != null ? gameConfig.getId() : "unknown");

            String boardResponse = provider.callVision(boardBytes, boardMedia, prompt);
            VisionResult result  = parseResponse(boardResponse);

            if (separateTiles) {
                byte[] tilesBytes = imageCropService.crop(rawBytes, tilesCrop);
                String extractedTiles = extractTilesFromImage(tilesBytes, provider);
                result.setExtractedTiles(extractedTiles);
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to extract board state from image", e);
            throw new RuntimeException("Board image analysis failed: " + e.getMessage(), e);
        }
    }

    // ── Provider resolution ───────────────────────────────────────────────────

    private VisionProvider resolveProvider(GameConfig gameConfig) {
        // Game config can override the global default
        String name = (gameConfig != null && gameConfig.getVisionProvider() != null)
                ? gameConfig.getVisionProvider()
                : defaultProviderName;

        VisionProvider provider = providers.get(name);
        if (provider == null) {
            log.warn("Unknown vision provider '{}', falling back to claude", name);
            provider = providers.get("claude");
        }
        if (!provider.isAvailable()) {
            log.warn("Provider '{}' is not available (API key missing?), falling back to claude", provider.getName());
            provider = providers.get("claude");
        }
        return provider;
    }

    // ── Tiles-only extraction ─────────────────────────────────────────────────

    private String extractTilesFromImage(byte[] tilesBytes, VisionProvider provider) {
        String prompt = """
                These are a player's Scrabble tiles. Read them left to right.
                Return ONLY valid JSON with no other text: {"tiles": "AEINRST"}
                Use uppercase letters A-Z. Use _ for a blank tile.
                If you cannot read the tiles, return: {"tiles": null}
                """;
        try {
            String response = provider.callVisionSimple(tilesBytes, "image/png", prompt);
            String content = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
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

    // ── Prompts ───────────────────────────────────────────────────────────────

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

    // ── Response parsing ──────────────────────────────────────────────────────

    private VisionResult parseResponse(String content) throws Exception {
        log.debug("Vision response: {}", content.length() > 500 ? content.substring(0, 500) + "..." : content);

        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (!content.startsWith("{")) {
            int start = content.indexOf('{');
            int end   = content.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start) {
                content = content.substring(start, end + 1);
            } else {
                log.error("Vision provider did not return JSON. Response: {}", content);
                throw new RuntimeException("Vision provider did not return valid JSON. Response: "
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
            int row = cellNode.path("row").asInt();
            int col = cellNode.path("col").asInt();
            if (row < 0 || row >= BoardState.SIZE || col < 0 || col >= BoardState.SIZE) continue;

            Cell cell = boardState.getCell(row, col);

            JsonNode sqNode = cellNode.path("squareType");
            if (!sqNode.isMissingNode() && !sqNode.isNull()) {
                try { cell.setSquareType(SquareType.valueOf(sqNode.asText())); }
                catch (IllegalArgumentException e) { cell.setSquareType(SquareType.STANDARD); }
            }

            JsonNode letterNode = cellNode.path("letter");
            if (!letterNode.isNull() && !letterNode.isMissingNode()) {
                String letterStr = letterNode.asText();
                if (!letterStr.isBlank()) cell.setLetter(letterStr.toUpperCase().charAt(0));
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
