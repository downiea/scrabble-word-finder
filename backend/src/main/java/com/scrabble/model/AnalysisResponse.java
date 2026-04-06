package com.scrabble.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AnalysisResponse {

    /** The board as parsed from the image — useful for the frontend to render a confirmation view. */
    private BoardState boardState;

    /** Top 5 suggestions, ranked best first. */
    private List<MoveSuggestion> suggestions;

    /** Any warnings from the vision parsing step (e.g. low-confidence tiles). */
    private List<String> warnings;

    /**
     * Rack tiles extracted from the image by Claude Vision, e.g. "AEINRST".
     * Null if the image did not include a visible rack or Claude was not confident.
     */
    private String extractedTiles;
}
