package com.scrabble.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * Request body for POST /api/analyse/board — used in step 2 when the board state
 * has already been extracted and confirmed by the user.
 */
@Data
public class AnalyseBoardRequest {

    /** Occupied cells on the board. Empty cells may be omitted. */
    @NotNull
    private List<CellData> cells;

    @Pattern(regexp = "[A-Z_]{1,7}", message = "Tiles must be 1-7 uppercase letters or underscores for blanks")
    private String tiles;

    @NotNull
    private String ruleset;

    private String gameConfigId;

    @Data
    public static class CellData {
        private int row;
        private int col;
        /** Single uppercase letter, or lowercase for a blank tile playing as that letter. */
        private String letter;
        /** Square multiplier type — STANDARD, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD */
        private String squareType;
    }
}
