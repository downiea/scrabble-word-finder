package com.scrabble.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AnalysisRequest {

    /**
     * The player's current rack — 1 to 7 letters, uppercase A-Z plus '_' for blank tiles.
     * Example: "AEINRST" or "AEIN_ST"
     */
    @NotBlank
    @Pattern(regexp = "[A-Z_]{1,7}", message = "Tiles must be 1-7 uppercase letters or underscores (blanks)")
    private String tiles;

    @NotNull
    private Ruleset ruleset;
}
