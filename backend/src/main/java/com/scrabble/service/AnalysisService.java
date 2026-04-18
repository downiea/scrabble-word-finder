package com.scrabble.service;

import com.scrabble.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final BoardVisionService boardVisionService;
    private final MoveGenerator moveGenerator;
    private final RankingService rankingService;
    private final GameConfigService gameConfigService;

    /** Step 1 — vision only, no move generation. */
    public ExtractResponse extract(MultipartFile image, String imageType, String gameConfigId, boolean debug) {
        log.info("Extracting board state imageType={} gameConfigId={} debug={}", imageType, gameConfigId, debug);
        GameConfig gameConfig = gameConfigService.getById(gameConfigId != null ? gameConfigId : "unknown");
        VisionResult visionResult = boardVisionService.extractBoardState(image, imageType, gameConfig, null, null, debug);

        // Overlay known board layout — overrides whatever squareTypes Claude may have returned
        if (gameConfig.getBoardLayout() != null && !gameConfig.getBoardLayout().isEmpty()) {
            applyBoardLayout(visionResult.getBoardState(), gameConfig);
        }

        String debugImage = null;
        if (debug && visionResult.getDebugEnhancedImageBytes() != null) {
            debugImage = java.util.Base64.getEncoder().encodeToString(visionResult.getDebugEnhancedImageBytes());
        }
        return ExtractResponse.builder()
                .boardState(visionResult.getBoardState())
                .extractedTiles(visionResult.getExtractedTiles())
                .warnings(visionResult.getWarnings() != null ? visionResult.getWarnings() : List.of())
                .debugEnhancedImageBase64(debugImage)
                .build();
    }

    private void applyBoardLayout(BoardState boardState, GameConfig gameConfig) {
        // First reset all squares to STANDARD
        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                Cell cell = boardState.getCell(r, c);
                if (cell != null) cell.setSquareType(SquareType.STANDARD);
            }
        }
        // Then apply the config's known positions
        for (var entry : gameConfig.getBoardLayout()) {
            Cell cell = boardState.getCell(entry.getRow(), entry.getCol());
            if (cell != null) {
                try {
                    cell.setSquareType(SquareType.valueOf(entry.getSquareType()));
                } catch (IllegalArgumentException e) {
                    cell.setSquareType(SquareType.STANDARD);
                }
            }
        }
    }

    /** Step 2 — move generation from a pre-confirmed board state. */
    public AnalysisResponse analyseFromBoard(AnalyseBoardRequest request) {
        Ruleset ruleset = Ruleset.valueOf(request.getRuleset().toUpperCase());
        GameConfig gameConfig = gameConfigService.getById(
                request.getGameConfigId() != null ? request.getGameConfigId() : "unknown");
        log.info("Analysing board tiles={} ruleset={} gameConfigId={}", request.getTiles(), ruleset, gameConfig.getId());

        BoardState boardState = new BoardState();
        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                boardState.setCell(Cell.builder()
                        .row(r).col(c)
                        .squareType(SquareType.STANDARD)
                        .build());
            }
        }

        if (request.getCells() != null) {
            for (AnalyseBoardRequest.CellData cd : request.getCells()) {
                Cell cell = boardState.getCell(cd.getRow(), cd.getCol());

                // Apply square type from the confirmed board
                if (cd.getSquareType() != null && !cd.getSquareType().isBlank()) {
                    try {
                        cell.setSquareType(SquareType.valueOf(cd.getSquareType()));
                    } catch (IllegalArgumentException e) {
                        cell.setSquareType(SquareType.STANDARD);
                    }
                }

                if (cd.getLetter() != null && !cd.getLetter().isBlank()) {
                    cell.setLetter(cd.getLetter().toUpperCase().charAt(0));
                }
            }
        }

        List<Move> candidates = moveGenerator.generateMoves(boardState, request.getTiles(), ruleset, gameConfig);
        List<MoveSuggestion> suggestions = rankingService.rank(candidates, boardState);

        return AnalysisResponse.builder()
                .boardState(boardState)
                .suggestions(suggestions)
                .warnings(List.of())
                .build();
    }
}
