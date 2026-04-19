package com.scrabble.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.*;
import com.scrabble.service.vision.VisionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
                // Grid transcription: model outputs a 15×15 letter grid directly
                result = gridTranscriptionExtract(boardBytes, boardMedia, gameConfig, provider, debug);
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

    // ── Grid transcription extraction ────────────────────────────────────────

    /**
     * Single-call approach: model outputs a 15×15 character grid directly.
     * Each cell is a letter (A-Z) if a player tile is present, or '.' if empty.
     * Coordinates are derived from array index — no coordinate translation by the model.
     */
    private VisionResult gridTranscriptionExtract(byte[] boardBytes, String boardMedia,
                                                  GameConfig gameConfig, VisionProvider provider, boolean debug) throws Exception {
        String response = provider.callVision(boardBytes, boardMedia, buildGridTranscriptionPrompt());

        String content = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (!content.startsWith("{")) {
            int s = content.indexOf('{'), e = content.lastIndexOf('}');
            if (s != -1 && e > s) content = content.substring(s, e + 1);
        }

        JsonNode parsed = objectMapper.readTree(content);
        JsonNode rowsNode = parsed.path("rows");

        BoardState boardState = initBoardStateFromLayout(gameConfig);
        List<String> debugCells = new ArrayList<>();

        for (int r = 0; r < Math.min(rowsNode.size(), BoardState.SIZE); r++) {
            String rowStr = rowsNode.get(r).asText();
            for (int c = 0; c < Math.min(rowStr.length(), BoardState.SIZE); c++) {
                char ch = rowStr.charAt(c);
                if (ch != '.' && Character.isLetter(ch)) {
                    boardState.getCell(r, c).setLetter(ch);
                    if (debug) debugCells.add(String.valueOf((char) ('A' + c)) + (r + 1) + "=" + ch);
                }
            }
        }

        log.info("Grid transcription: {} tiles found", debugCells.size());

        List<String> warnings = new ArrayList<>();
        if (debug) {
            warnings.add("[Grid] " + debugCells.size() + " tiles: " + String.join(", ", debugCells));
        }

        return VisionResult.builder()
                .boardState(boardState)
                .warnings(warnings)
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

    // ── Boundary check pass ───────────────────────────────────────────────────

    private static final String CELL_CHECK_PROMPT = """
            This is a single cell cropped from a Scrabble board.
            Does it contain a player-placed letter tile?

            A tile is a solid coloured rectangular piece with a LARGE letter (A-Z) in the centre
            and a small point-value number (e.g. 1, 3, 8, 10) in its corner.
            An empty cell shows only a bonus label (2L, DL, 3W, TW), a star, or a plain background.

            Reply with ONLY ONE character:
            - The uppercase letter (A-Z) if a tile is present
            - A period "." if the cell is empty

            Nothing else. A single character only.
            """;

    private static final int[][] DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /**
     * For every cell on the boundary between occupied and empty (both sides),
     * crops the cell from the enhanced image and asks the vision provider to confirm
     * whether a tile is present. Corrections are applied in-place to boardState.
     * Runs all cell checks in parallel.
     */
    private void boundaryCheckPass(byte[] boardBytes, BoardState boardState,
                                   VisionProvider provider, boolean debug, List<String> debugCells) {
        BufferedImage boardImg;
        try {
            boardImg = ImageIO.read(new ByteArrayInputStream(boardBytes));
            if (boardImg == null) { log.warn("Boundary check: could not read board image"); return; }
        } catch (Exception e) {
            log.warn("Boundary check: image read failed: {}", e.getMessage()); return;
        }

        int W = boardImg.getWidth();
        int H = boardImg.getHeight();

        boolean[][] occ = new boolean[BoardState.SIZE][BoardState.SIZE];
        for (int r = 0; r < BoardState.SIZE; r++)
            for (int c = 0; c < BoardState.SIZE; c++)
                occ[r][c] = boardState.getCell(r, c).getLetter() != null;

        // Include any cell touching a boundary between occupied and empty
        List<int[]> zone = new ArrayList<>();
        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                for (int[] d : DIRS) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr >= 0 && nr < BoardState.SIZE && nc >= 0 && nc < BoardState.SIZE
                            && occ[nr][nc] != occ[r][c]) {
                        zone.add(new int[]{r, c});
                        break;
                    }
                }
            }
        }

        log.info("Boundary check: verifying {} cells in parallel", zone.size());

        BufferedImage imgRef = boardImg;
        List<CompletableFuture<Void>> futures = zone.stream().map(cell ->
            CompletableFuture.runAsync(() -> {
                int r = cell[0], c = cell[1];
                try {
                    byte[] cellBytes = cropCell(imgRef, W, H, r, c);
                    String raw = provider.callVisionSimple(cellBytes, "image/png", CELL_CHECK_PROMPT).trim();
                    Character result = parseCellResponse(raw);
                    if (result == null) return;

                    Cell boardCell = boardState.getCell(r, c);
                    if (result == '.') {
                        if (boardCell.getLetter() != null) {
                            log.info("Boundary check: removed false tile at ({},{}) was '{}'", r, c, boardCell.getLetter());
                            boardCell.setLetter(null);
                            if (debug) debugCells.removeIf(s -> s.startsWith(String.valueOf((char)('A'+c)) + (r+1) + "="));
                        }
                    } else {
                        if (!result.equals(boardCell.getLetter())) {
                            log.info("Boundary check: corrected ({},{}) '{}' -> '{}'", r, c, boardCell.getLetter(), result);
                            boardCell.setLetter(result);
                            if (debug) {
                                String tag = String.valueOf((char) ('A' + c)) + (r + 1) + "=" + result;
                                debugCells.removeIf(s -> s.startsWith(String.valueOf((char)('A'+c)) + (r+1) + "="));
                                debugCells.add(tag + "*");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Boundary check cell ({},{}): {}", r, c, e.getMessage());
                }
            })
        ).collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Boundary check complete");
    }

    private Character parseCellResponse(String response) {
        if (response == null || response.isBlank()) return null;
        String trimmed = response.trim();
        if (trimmed.length() == 1) {
            char ch = trimmed.charAt(0);
            if (ch == '.') return '.';
            if (Character.isLetter(ch)) return Character.toUpperCase(ch);
        }
        if (trimmed.equalsIgnoreCase("empty") || trimmed.startsWith(".")) return '.';
        for (String word : trimmed.split("\\s+")) {
            if (word.length() == 1 && Character.isLetter(word.charAt(0))) return Character.toUpperCase(word.charAt(0));
        }
        return null;
    }

    private byte[] cropCell(BufferedImage img, int W, int H, int r, int c) throws Exception {
        int x = (int) Math.round((double) c * W / BoardState.SIZE);
        int y = (int) Math.round((double) r * H / BoardState.SIZE);
        int w = Math.max(1, (int) Math.round((double) (c + 1) * W / BoardState.SIZE) - x);
        int h = Math.max(1, (int) Math.round((double) (r + 1) * H / BoardState.SIZE) - y);
        w = Math.min(w, W - x);
        h = Math.min(h, H - y);
        BufferedImage cell = img.getSubimage(x, y, w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(cell, "png", out);
        return out.toByteArray();
    }

    private String buildGridTranscriptionPrompt() {
        return """
                Transcribe this Scrabble board as a plain-text letter grid.

                The board has a 15×15 grid overlay. Column labels A–O run along the top edge;
                row labels 1–15 run down the left edge.

                Output exactly 15 rows, each containing exactly 15 characters:
                - Use the UPPERCASE LETTER (A–Z) for cells that have a player-placed tile on them.
                - Use '.' for every other cell — empty cells, bonus squares (2L, DL, 3W, TW, etc.),
                  the center star, or any square without a physical tile piece.

                HOW TO IDENTIFY A PLAYER TILE:
                A tile is a solid-coloured rectangular piece sitting in the cell with a LARGE letter
                in the centre and a small point-value number (e.g. 1, 3, 8, 10) in its corner.
                Bonus labels like "2L", "DL", "3W" on empty coloured squares are NOT tiles — use '.'.

                Scan strictly left to right, then top to bottom (row 1 first, row 15 last;
                column A first, column O last). Use the grid lines to count cells precisely.

                Return ONLY valid JSON with no other text:
                {"rows": [
                  "...............",
                  "...............",
                  ".......S.......",
                  "......ATE......",
                  ".......R.......",
                  "...............",
                  "...............",
                  "...............",
                  "...............",
                  "...............",
                  "...............",
                  "...............",
                  "...............",
                  "...............",
                  "..............."
                ]}
                Each string must be exactly 15 characters. Use only letters A–Z and '.'.
                """;
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
