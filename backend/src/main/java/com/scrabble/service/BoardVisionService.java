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
    private final ImageEnhancementService imageEnhancementService;

    public BoardVisionService(List<VisionProvider> providerList,
                              ObjectMapper objectMapper,
                              ImageCropService imageCropService,
                              ImageEnhancementService imageEnhancementService) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(VisionProvider::getName, Function.identity()));
        this.objectMapper = objectMapper;
        this.imageCropService = imageCropService;
        this.imageEnhancementService = imageEnhancementService;
        log.info("Vision providers registered: {}", this.providers.keySet());
    }

    public VisionResult extractBoardState(MultipartFile imageFile, String imageType, GameConfig gameConfig) {
        return extractBoardState(imageFile, imageType, gameConfig, null, null, false);
    }

    public VisionResult extractBoardState(MultipartFile imageFile, String imageType, GameConfig gameConfig,
                                           CropRegion boardCropOverride, CropRegion tilesCropOverride, boolean debug) {
        try {
            byte[] rawBytes = imageFile.getBytes();
            String mediaType = imageFile.getContentType() != null ? imageFile.getContentType() : "image/jpeg";

            CropRegion boardCrop = boardCropOverride != null ? boardCropOverride
                    : (gameConfig != null ? gameConfig.getBoardCrop() : null);
            CropRegion tilesCrop = tilesCropOverride != null ? tilesCropOverride
                    : (gameConfig != null ? gameConfig.getTilesCrop() : null);

            byte[] boardBytes = boardCrop != null ? imageCropService.crop(rawBytes, boardCrop) : rawBytes;
            boardBytes = imageEnhancementService.enhanceForVision(boardBytes);
            String boardMedia = "image/png";
            byte[] debugBytes = debug ? boardBytes : null;

            boolean layoutKnown   = gameConfig != null && gameConfig.getBoardLayout() != null && !gameConfig.getBoardLayout().isEmpty();
            boolean isPhysical    = "PHYSICAL".equalsIgnoreCase(imageType);
            boolean separateTiles = tilesCrop != null;

            VisionProvider provider = resolveProvider(gameConfig);
            log.info("Using vision provider: {} for gameConfig: {}", provider.getName(),
                    gameConfig != null ? gameConfig.getId() : "unknown");

            VisionResult result;
            if (layoutKnown) {
                // Two-step: first identify occupied cells, then read their letters
                result = twoStepExtract(boardBytes, boardMedia, gameConfig, provider);
            } else {
                String prompt = isPhysical
                        ? buildPhysicalPrompt(gameConfig, separateTiles)
                        : buildDigitalPrompt(gameConfig, separateTiles);
                String boardResponse = provider.callVision(boardBytes, boardMedia, prompt);
                result = parseResponse(boardResponse);
            }

            result.setDebugEnhancedImageBytes(debugBytes);

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

    // ── Two-step extraction ───────────────────────────────────────────────────

    /**
     * Step 1: ask the model which cells have player tiles (ignoring bonus labels).
     * Step 2: ask the model to read the letter in each of those cells only.
     * This eliminates confusion between bonus-square labels and played tiles, and
     * focuses the letter-reading pass on a small target set rather than all 225 cells.
     */
    private VisionResult twoStepExtract(byte[] boardBytes, String boardMedia,
                                        GameConfig gameConfig, VisionProvider provider) throws Exception {
        // Step 1 — which cells are occupied?
        String step1Response = provider.callVision(boardBytes, boardMedia, buildOccupiedCellsPrompt());
        List<int[]> occupied = parseOccupiedCells(step1Response);
        log.info("Two-step extraction step 1: {} occupied cells found", occupied.size());

        BoardState boardState = initBoardStateFromLayout(gameConfig);

        if (!occupied.isEmpty()) {
            // Step 2 — read the letter in each occupied cell
            String step2Response = provider.callVision(boardBytes, boardMedia, buildReadLettersPrompt(occupied));
            applyLettersToBoard(step2Response, boardState);
        }

        return VisionResult.builder()
                .boardState(boardState)
                .warnings(new ArrayList<>())
                .build();
    }

    /** Creates a BoardState pre-populated with squareTypes from the game config's boardLayout. */
    private BoardState initBoardStateFromLayout(GameConfig config) {
        BoardState board = new BoardState();
        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                board.setCell(Cell.builder().row(r).col(c).squareType(SquareType.STANDARD).build());
            }
        }
        if (config != null && config.getBoardLayout() != null) {
            for (BoardLayoutEntry entry : config.getBoardLayout()) {
                Cell cell = board.getCell(entry.getRow(), entry.getCol());
                if (cell != null) {
                    try { cell.setSquareType(SquareType.valueOf(entry.getSquareType())); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return board;
    }

    private String buildOccupiedCellsPrompt() {
        return """
                Look at this Scrabble board. A 15×15 grid is overlaid with:
                - Column labels A–O along the top edge
                - Row labels 1–15 along the left edge
                - A small coordinate tag (e.g. "A1", "C7") in the top-left corner of EVERY cell

                Your task: identify every cell that has a PLAYER-PLACED LETTER TILE on it.

                A player tile is a solid coloured square/rectangle with a single LARGE letter on it.

                DO NOT include empty cells that show any of these — they are board markings, not tiles:
                - Bonus labels: "2L", "DL", "3L", "TL", "2W", "DW", "3W", "TW"
                - The center star symbol
                - The small coordinate tags in the corners (e.g. "A1") — these are overlay labels

                Use the coordinate tag visible in each cell to determine its exact position.
                Row 0 = row labelled "1", col 0 = column labelled "A".

                Return ONLY valid JSON with no other text:
                {"occupied": [[row, col], [row, col], ...]}

                Example — tiles at B2 and H8: {"occupied": [[1,1],[7,7]]}
                If the board is empty: {"occupied": []}
                """;
    }

    private List<int[]> parseOccupiedCells(String response) {
        try {
            String content = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            if (!content.startsWith("{")) {
                int s = content.indexOf('{'), e = content.lastIndexOf('}');
                if (s != -1 && e > s) content = content.substring(s, e + 1);
                else return List.of();
            }
            JsonNode parsed = objectMapper.readTree(content);
            List<int[]> result = new ArrayList<>();
            for (JsonNode cell : parsed.path("occupied")) {
                if (cell.isArray() && cell.size() == 2) {
                    int row = cell.get(0).asInt();
                    int col = cell.get(1).asInt();
                    if (row >= 0 && row < BoardState.SIZE && col >= 0 && col < BoardState.SIZE) {
                        result.add(new int[]{row, col});
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse occupied cells response: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildReadLettersPrompt(List<int[]> occupiedCells) {
        StringBuilder cellList = new StringBuilder();
        for (int[] cell : occupiedCells) {
            char col = (char) ('A' + cell[1]);
            int row = cell[0] + 1;
            cellList.append(String.format("  - %c%d (row=%d, col=%d)%n", col, row, cell[0], cell[1]));
        }
        return """
                Look at this Scrabble board. A 15×15 grid is overlaid with column labels A–O (top),
                row labels 1–15 (left), and a small coordinate tag in the top-left corner of every cell.

                The following cells are known to contain player-placed letter tiles:
                %s
                For each cell listed:
                1. Locate the cell using its coordinate tag (e.g. find the cell showing "C7" in its corner).
                2. Read the LARGE letter on the tile — this is the player's letter.
                3. Ignore any smaller bonus label you may see (2L, DL, 3W, etc.) — focus only on the tile letter.
                4. A blank tile playing as a specific letter: return that letter in lowercase.

                Return ONLY valid JSON with no other text:
                {
                  "cells": [
                    {"row": 0, "col": 0, "letter": "A"},
                    {"row": 7, "col": 7, "letter": "S"}
                  ],
                  "warnings": []
                }

                Only include the cells listed above. Add a warning only if a specific cell is physically
                obscured or cut off and you cannot read it confidently.
                """.formatted(cellList);
    }

    private void applyLettersToBoard(String response, BoardState boardState) {
        try {
            String content = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            if (!content.startsWith("{")) {
                int s = content.indexOf('{'), e = content.lastIndexOf('}');
                if (s != -1 && e > s) content = content.substring(s, e + 1);
                else { log.warn("No JSON found in step-2 response"); return; }
            }
            JsonNode parsed = objectMapper.readTree(content);
            for (JsonNode cellNode : parsed.path("cells")) {
                int row = cellNode.path("row").asInt();
                int col = cellNode.path("col").asInt();
                if (row < 0 || row >= BoardState.SIZE || col < 0 || col >= BoardState.SIZE) continue;
                JsonNode letterNode = cellNode.path("letter");
                if (!letterNode.isNull() && !letterNode.isMissingNode()) {
                    String letterStr = letterNode.asText();
                    if (!letterStr.isBlank()) {
                        boardState.getCell(row, col).setLetter(letterStr.charAt(0));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply letters to board from step-2 response: {}", e.getMessage());
        }
    }

    // ── Provider resolution ───────────────────────────────────────────────────

    private VisionProvider resolveProvider(GameConfig gameConfig) {
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

    // ── Single-step prompts (used when board layout is unknown) ───────────────

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

                A 15×15 grid has been drawn over the image. Column labels A–O are along the top;
                row labels 1–15 are on the left. A small coordinate tag (e.g. "C7") also appears
                in the top-left corner of every cell — use these as position anchors.
                Row 0 = label 1, col 0 = label A.

                IMPORTANT — distinguishing tiles from bonus labels:
                - A PLAYER TILE is a solid coloured square with a single LARGE letter on it.
                - Bonus square labels (2L, DL, 3L, TL, 2W, DW, 3W, TW) on EMPTY squares are NOT tiles.
                  Only read a letter if there is an actual tile piece there.

                Extract:
                1. BOARD STATE — the grid of played tiles AND the multiplier type of every square.
                   Ignore all UI chrome, menus, score panels outside the grid lines.
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

                A 15×15 grid has been drawn over the image with column labels A–O (top),
                row labels 1–15 (left), and a small coordinate tag in every cell's top-left corner.
                Use these as your position reference. Row 0 = label 1, col 0 = label A.

                IMPORTANT — distinguishing tiles from bonus labels:
                - A PLAYER TILE is a physical wooden or plastic piece with a large embossed/printed letter.
                - Board square markings (DL, TL, DW, TW, coloured squares) on EMPTY squares are NOT tiles.

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

    // ── Response parsing (used by single-step paths) ──────────────────────────

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
