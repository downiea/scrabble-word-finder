package com.scrabble.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExtractResponse {
    private BoardState boardState;
    private String extractedTiles;
    private List<String> warnings;
    /** Base64-encoded PNG of the enhanced image sent to the vision API. Only populated in debug mode. */
    private String debugEnhancedImageBase64;
}
