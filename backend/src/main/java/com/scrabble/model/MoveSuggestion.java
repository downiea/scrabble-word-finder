package com.scrabble.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A fully scored and ranked move suggestion returned to the frontend.
 */
@Data
@Builder
public class MoveSuggestion {

    private int rank;
    private String word;
    private int startRow;
    private int startCol;
    private Direction direction;
    private int rawScore;
    private int strategyScore;
    private int totalScore;

    /** Human-readable reasons for the strategy adjustments applied. */
    private List<String> strategyReasons;
}
