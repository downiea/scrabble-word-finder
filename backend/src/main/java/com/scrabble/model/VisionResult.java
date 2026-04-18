package com.scrabble.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Result returned by the Claude Vision step — the parsed board state plus
 * any rack tiles the model was able to read from the image.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionResult {

    private BoardState boardState;

    /**
     * Rack tiles extracted from the image, e.g. "AEINRST".
     * Null if the image did not show the player's rack or Claude was not confident.
     */
    private String extractedTiles;

    private java.util.List<String> warnings;
    /** Enhanced image bytes sent to the vision API — only populated when debug=true. */
    private byte[] debugEnhancedImageBytes;
}
