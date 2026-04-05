package com.scrabble.controller;

import com.scrabble.model.AnalysisResponse;
import com.scrabble.model.Ruleset;
import com.scrabble.service.AnalysisService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    /**
     * Analyse a Scrabble board image and return the top 5 move suggestions.
     *
     * @param image   the board image (JPEG or PNG)
     * @param tiles   the player's rack — 1-7 uppercase letters, '_' for blank tiles
     * @param ruleset US or UK
     */
    @PostMapping(value = "/analyse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisResponse> analyse(
            @RequestPart("image") MultipartFile image,
            @RequestPart("tiles")
            @NotBlank
            @Pattern(regexp = "[A-Z_]{1,7}", message = "Tiles must be 1-7 uppercase letters or underscores for blanks")
            String tiles,
            @RequestPart("ruleset")
            @NotNull
            String ruleset) {

        Ruleset rulesetEnum = Ruleset.valueOf(ruleset.toUpperCase());
        AnalysisResponse response = analysisService.analyse(image, tiles, rulesetEnum);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
