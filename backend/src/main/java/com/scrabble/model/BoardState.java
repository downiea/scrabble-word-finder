package com.scrabble.model;

import lombok.Data;

/**
 * Represents the full 15x15 Scrabble board extracted from the image.
 * grid[row][col] — row 0 is the top row, col 0 is the leftmost column.
 */
@Data
public class BoardState {

    public static final int SIZE = 15;

    private final Cell[][] grid = new Cell[SIZE][SIZE];

    public Cell getCell(int row, int col) {
        return grid[row][col];
    }

    public void setCell(Cell cell) {
        grid[cell.getRow()][cell.getCol()] = cell;
    }

    public boolean isEmpty() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (grid[r][c] != null && !grid[r][c].isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the standard Scrabble board square type layout.
     * This is the same for both US and UK editions.
     */
    public static SquareType standardSquareType(int row, int col) {
        // Normalise to top-left quadrant using symmetry
        int r = Math.min(row, 14 - row);
        int c = Math.min(col, 14 - col);

        // Triple word squares
        if ((r == 0 && (c == 0 || c == 7)) || (r == 7 && c == 0)) return SquareType.TRIPLE_WORD;
        // Double word squares (includes centre)
        if (r == c && r <= 4) return SquareType.DOUBLE_WORD;
        if ((r == 0 && c == 3) || (r == 3 && c == 0)) return SquareType.DOUBLE_WORD;
        if (r == 7 && c == 7) return SquareType.DOUBLE_WORD; // centre
        // Triple letter squares
        if ((r == 1 && (c == 1 || c == 5)) || (r == 5 && (c == 1 || c == 5))) return SquareType.TRIPLE_LETTER;
        // Double letter squares
        if ((r == 0 && c == 3) || (r == 2 && (c == 1 || c == 3)) || (r == 3 && (c == 0 || c == 4)) || (r == 4 && c == 3)) return SquareType.DOUBLE_LETTER;
        if (r == 6 && (c == 2 || c == 6)) return SquareType.DOUBLE_LETTER;
        if (r == 7 && c == 3) return SquareType.DOUBLE_LETTER;

        return SquareType.STANDARD;
    }
}
