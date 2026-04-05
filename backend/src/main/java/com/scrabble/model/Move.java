package com.scrabble.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A candidate move: the word placed, where it starts, which direction,
 * and which tiles from the rack are used (blanks represented as '_').
 */
@Data
@Builder
public class Move {

    private String word;
    private int startRow;
    private int startCol;
    private Direction direction;

    /** Tiles consumed from the player's rack for this move. */
    private List<Character> tilesUsed;

    private int rawScore;
}
