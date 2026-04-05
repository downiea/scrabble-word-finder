package com.scrabble.service;

import com.scrabble.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates all legal Scrabble moves for a given board state and player rack.
 *
 * Uses a cross-check approach:
 * 1. Find all anchor squares (empty squares adjacent to filled squares, or centre on first move).
 * 2. For each anchor, extend words left/right (ACROSS) and up/down (DOWN).
 * 3. Validate each candidate word against the dictionary and cross-words.
 * 4. Score valid placements.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoveGenerator {

    private final WordListEngine wordListEngine;

    // Scrabble letter values — shared between US and UK editions
    private static final int[] LETTER_VALUES = {
        1, 3, 3, 2, 1, 4, 2, 4, 1, 8, 5, 1, 3,
        1, 1, 3, 10, 1, 1, 1, 1, 4, 4, 8, 4, 10
        // A  B  C  D  E  F  G  H  I  J  K  L  M
        // N  O  P  Q  R  S  T  U  V  W  X  Y  Z
    };

    private static final int BINGO_BONUS = 50;
    private static final int RACK_SIZE = 7;

    public List<Move> generateMoves(BoardState board, String rack, Ruleset ruleset) {
        List<Move> moves = new ArrayList<>();
        char[] rackChars = rack.toUpperCase().toCharArray();

        for (Direction direction : Direction.values()) {
            for (int row = 0; row < BoardState.SIZE; row++) {
                for (int col = 0; col < BoardState.SIZE; col++) {
                    if (isAnchor(board, row, col)) {
                        generateMovesFromAnchor(board, row, col, direction, rackChars, ruleset, moves);
                    }
                }
            }
        }

        log.debug("Generated {} candidate moves", moves.size());
        return moves;
    }

    private boolean isAnchor(BoardState board, int row, int col) {
        Cell cell = board.getCell(row, col);
        if (cell == null || !cell.isEmpty()) return false;

        // Centre square is anchor on first move
        if (row == 7 && col == 7 && board.isEmpty()) return true;

        // Otherwise must be adjacent to a filled cell
        int[][] neighbours = {{row - 1, col}, {row + 1, col}, {row, col - 1}, {row, col + 1}};
        for (int[] n : neighbours) {
            if (n[0] < 0 || n[0] >= BoardState.SIZE || n[1] < 0 || n[1] >= BoardState.SIZE) continue;
            Cell neighbour = board.getCell(n[0], n[1]);
            if (neighbour != null && !neighbour.isEmpty()) return true;
        }
        return false;
    }

    private void generateMovesFromAnchor(BoardState board, int anchorRow, int anchorCol,
                                          Direction direction, char[] rack, Ruleset ruleset,
                                          List<Move> results) {
        // Find how far back we can start a word before this anchor
        int maxBack = 0;
        int r = anchorRow, c = anchorCol;
        while (true) {
            r -= (direction == Direction.DOWN ? 1 : 0);
            c -= (direction == Direction.ACROSS ? 1 : 0);
            if (r < 0 || r >= BoardState.SIZE || c < 0 || c >= BoardState.SIZE) break;
            Cell cell = board.getCell(r, c);
            if (cell == null || !cell.isEmpty()) break;
            maxBack++;
        }

        // Try all possible starting positions
        for (int back = 0; back <= maxBack; back++) {
            int startRow = anchorRow - (direction == Direction.DOWN ? back : 0);
            int startCol = anchorCol - (direction == Direction.ACROSS ? back : 0);
            extendWord(board, startRow, startCol, startRow, startCol, direction,
                    new ArrayList<>(java.util.Arrays.asList(toCharList(rack))), "",
                    new ArrayList<>(), ruleset, results);
        }
    }

    private void extendWord(BoardState board, int startRow, int startCol,
                             int row, int col, Direction direction,
                             List<Character> remainingRack, String currentWord,
                             List<Character> tilesUsed, Ruleset ruleset,
                             List<Move> results) {
        if (row < 0 || row >= BoardState.SIZE || col < 0 || col >= BoardState.SIZE) {
            // Word has reached the edge — validate if long enough
            if (currentWord.length() >= 2) tryAddMove(board, startRow, startCol, direction, currentWord, tilesUsed, ruleset, results);
            return;
        }

        Cell current = board.getCell(row, col);
        int nextRow = row + (direction == Direction.DOWN ? 1 : 0);
        int nextCol = col + (direction == Direction.ACROSS ? 1 : 0);

        if (current != null && !current.isEmpty()) {
            // Use the existing letter on the board
            String nextWord = currentWord + current.getLetter();
            if (wordListEngine.isValidPrefix(nextWord, ruleset)) {
                extendWord(board, startRow, startCol, nextRow, nextCol, direction,
                        remainingRack, nextWord, new ArrayList<>(tilesUsed), ruleset, results);
            }
        } else {
            // Try each tile in the rack
            for (int i = 0; i < remainingRack.size(); i++) {
                char tile = remainingRack.get(i);
                List<Character> candidates = tile == '_' ? blankCandidates() : List.of(tile);

                for (char letter : candidates) {
                    String nextWord = currentWord + letter;
                    if (!wordListEngine.isValidPrefix(nextWord, ruleset)) continue;

                    List<Character> newRack = new ArrayList<>(remainingRack);
                    newRack.remove(i);
                    List<Character> newTilesUsed = new ArrayList<>(tilesUsed);
                    newTilesUsed.add(tile);

                    // Validate cross-word at this position
                    if (!isCrossWordValid(board, row, col, letter, direction, ruleset)) continue;

                    // Recurse to next position
                    extendWord(board, startRow, startCol, nextRow, nextCol, direction,
                            newRack, nextWord, newTilesUsed, ruleset, results);

                    // Also stop here if word is valid and long enough
                    if (nextWord.length() >= 2 && wordListEngine.isValidWord(nextWord, ruleset)
                            && mustPassThroughAnchor(board, startRow, startCol, row, col, direction)) {
                        tryAddMove(board, startRow, startCol, direction, nextWord, newTilesUsed, ruleset, results);
                    }
                }
            }
        }
    }

    private boolean isCrossWordValid(BoardState board, int row, int col, char letter,
                                      Direction mainDirection, Ruleset ruleset) {
        Direction perp = mainDirection == Direction.ACROSS ? Direction.DOWN : Direction.ACROSS;
        StringBuilder crossWord = new StringBuilder();
        crossWord.append(letter);

        // Scan backwards in perpendicular direction
        StringBuilder before = new StringBuilder();
        int r = row - (perp == Direction.DOWN ? 1 : 0);
        int c = col - (perp == Direction.ACROSS ? 1 : 0);
        while (r >= 0 && c >= 0 && r < BoardState.SIZE && c < BoardState.SIZE) {
            Cell cell = board.getCell(r, c);
            if (cell == null || cell.isEmpty()) break;
            before.insert(0, cell.getLetter());
            r -= (perp == Direction.DOWN ? 1 : 0);
            c -= (perp == Direction.ACROSS ? 1 : 0);
        }

        // Scan forwards
        StringBuilder after = new StringBuilder();
        r = row + (perp == Direction.DOWN ? 1 : 0);
        c = col + (perp == Direction.ACROSS ? 1 : 0);
        while (r >= 0 && c >= 0 && r < BoardState.SIZE && c < BoardState.SIZE) {
            Cell cell = board.getCell(r, c);
            if (cell == null || cell.isEmpty()) break;
            after.append(cell.getLetter());
            r += (perp == Direction.DOWN ? 1 : 0);
            c += (perp == Direction.ACROSS ? 1 : 0);
        }

        String full = before.toString() + letter + after.toString();
        return full.length() == 1 || wordListEngine.isValidWord(full, ruleset);
    }

    private boolean mustPassThroughAnchor(BoardState board, int startRow, int startCol,
                                           int row, int col, Direction direction) {
        // The word must cover at least one anchor square or existing tile
        for (int i = startRow; i <= row; i++) {
            for (int j = startCol; j <= col; j++) {
                Cell cell = board.getCell(i, j);
                if (cell != null && !cell.isEmpty()) return true;
                if (isAnchor(board, i, j)) return true;
            }
        }
        return false;
    }

    private void tryAddMove(BoardState board, int startRow, int startCol, Direction direction,
                             String word, List<Character> tilesUsed, Ruleset ruleset,
                             List<Move> results) {
        if (!wordListEngine.isValidWord(word, ruleset)) return;

        int score = calculateScore(board, startRow, startCol, direction, word, tilesUsed.size());
        results.add(Move.builder()
                .word(word)
                .startRow(startRow)
                .startCol(startCol)
                .direction(direction)
                .tilesUsed(new ArrayList<>(tilesUsed))
                .rawScore(score)
                .build());
    }

    private int calculateScore(BoardState board, int startRow, int startCol,
                                Direction direction, String word, int tilesFromRack) {
        int score = 0;
        int wordMultiplier = 1;

        for (int i = 0; i < word.length(); i++) {
            int row = startRow + (direction == Direction.DOWN ? i : 0);
            int col = startCol + (direction == Direction.ACROSS ? i : 0);
            Cell cell = board.getCell(row, col);

            char letter = word.charAt(i);
            int letterValue = letterValue(letter);

            if (cell != null && cell.isEmpty()) {
                // New tile — apply premium square
                SquareType squareType = BoardState.standardSquareType(row, col);
                switch (squareType) {
                    case DOUBLE_LETTER -> score += letterValue * 2;
                    case TRIPLE_LETTER -> score += letterValue * 3;
                    case DOUBLE_WORD -> { score += letterValue; wordMultiplier *= 2; }
                    case TRIPLE_WORD -> { score += letterValue; wordMultiplier *= 3; }
                    default -> score += letterValue;
                }
            } else {
                score += letterValue;
            }
        }

        score *= wordMultiplier;

        if (tilesFromRack == RACK_SIZE) {
            score += BINGO_BONUS;
        }

        return score;
    }

    private int letterValue(char c) {
        if (c < 'A' || c > 'Z') return 0;
        return LETTER_VALUES[c - 'A'];
    }

    private List<Character> toCharList(char[] chars) {
        List<Character> list = new ArrayList<>();
        for (char c : chars) list.add(c);
        return list;
    }

    private List<Character> blankCandidates() {
        List<Character> letters = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) letters.add(c);
        return letters;
    }
}
