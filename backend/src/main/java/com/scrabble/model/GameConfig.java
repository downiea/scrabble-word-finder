package com.scrabble.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GameConfig {
    private String id;
    private String name;
    private String description;
    private int boardSize;
    private String multiplierHint;
    /**
     * Letter point values. Keys are single uppercase letters A-Z.
     * If null, the standard Scrabble values are used.
     */
    private Map<String, Integer> letterValues;

    /**
     * Bonus points for using all rack tiles in one turn (bingo/sweep).
     * Defaults to 50 if not set. NYT Crossplay uses 40.
     */
    private Integer bingoBonus;

    /**
     * Known positions of non-standard squares. When set, the vision prompt
     * skips multiplier detection entirely and Claude only reads tile letters.
     * Null or empty means Claude must infer positions from the image.
     */
    private List<BoardLayoutEntry> boardLayout;

    /**
     * Row offset to apply to all cells returned by Claude.
     * Use a negative value to shift tiles up (e.g. -1 if Claude reads 1 row too low).
     */
    private int rowOffset;

    /**
     * Column offset to apply to all cells returned by Claude.
     * Use a negative value to shift tiles left.
     */
    private int colOffset;

    /**
     * Crop region for the board area. When set, only this portion of the image
     * is sent to Claude for board reading.
     */
    private CropRegion boardCrop;

    /**
     * Crop region for the player's tile rack. When set, this portion is sent
     * to Claude in a separate lightweight call to read the rack tiles.
     */
    private CropRegion tilesCrop;
}
