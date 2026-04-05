package com.scrabble.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cell {

    private int row;
    private int col;

    /** Letter already on this cell, or null if empty. */
    private Character letter;

    private SquareType squareType;

    public boolean isEmpty() {
        return letter == null;
    }
}
