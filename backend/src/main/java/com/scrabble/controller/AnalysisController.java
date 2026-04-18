package com.scrabble.controller;

import com.scrabble.model.AnalyseBoardRequest;
import com.scrabble.model.AnalysisResponse;
import com.scrabble.model.ExtractResponse;
import com.scrabble.model.GameConfig;
import com.scrabble.model.Ruleset;
import com.scrabble.model.UpdateCropsRequest;
import com.scrabble.service.AnalysisService;
import com.scrabble.service.GameConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class AnalysisController {

    private final AnalysisService analysisService;
    private final GameConfigService gameConfigService;

    /**
     * Step 1 — extract board state and rack tiles from an image.
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExtractResponse> extract(
            @RequestPart("image") @NotNull MultipartFile image,
            @RequestPart(value = "imageType", required = false) String imageType,
            @RequestPart(value = "gameConfigId", required = false) String gameConfigId,
            @RequestPart(value = "debug", required = false) String debugParam) {

        String effectiveType = (imageType != null && !imageType.isBlank()) ? imageType : "DIGITAL";
        boolean debug = "true".equalsIgnoreCase(debugParam);
        return ResponseEntity.ok(analysisService.extract(image, effectiveType, gameConfigId, debug));
    }

    /**
     * Step 2 — generate move suggestions from a confirmed board state.
     */
    @PostMapping(value = "/analyse/board", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisResponse> analyseBoard(@RequestBody @Valid AnalyseBoardRequest request) {
        return ResponseEntity.ok(analysisService.analyseFromBoard(request));
    }

    @GetMapping("/game-configs")
    public ResponseEntity<java.util.List<GameConfig>> gameConfigs() {
        return ResponseEntity.ok(gameConfigService.getAll());
    }

    @PatchMapping("/game-configs/{id}/crops")
    public ResponseEntity<GameConfig> updateCrops(@PathVariable String id,
                                                   @RequestBody UpdateCropsRequest request) {
        gameConfigService.updateCrops(id, request.getBoardCrop(), request.getTilesCrop());
        return ResponseEntity.ok(gameConfigService.getById(id));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
