package com.scrabble.model;

import lombok.Data;

@Data
public class BoardLayoutEntry {
    private int row;
    private int col;
    /** STANDARD, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD */
    private String squareType;
}
