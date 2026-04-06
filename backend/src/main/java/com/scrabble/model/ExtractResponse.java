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
}
