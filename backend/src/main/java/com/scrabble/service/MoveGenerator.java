package com.scrabble.service;

import com.scrabble.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates all legal Scrabble moves for a given board state and player rack.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoveGenerator {

    private final WordListEngine wordListEngine;

    // Standard Scrabble letter values — used when no game config is provided
    private static final Map<Character, Integer> DEFAULT_LETTER_VALUES = Map.ofEntries(
        Map.entry('A', 1), Map.entry('B', 3), Map.entry('C', 3), Map.entry('D', 2),
        Map.entry('E', 1), Map.entry('F', 4), Map.entry('G', 2), Map.entry('H', 4),
        Map.entry('I', 1), Map.entry('J', 8), Map.entry('K', 5), Map.entry('L', 1),
        Map.entry('M', 3), Map.entry('N', 1), Map.entry('O', 1), Map.entry('P', 3),
        Map.entry('Q', 10), Map.entry('R', 1), Map.entry('S', 1), Map.entry('T', 1),
        Map.entry('U', 1), Map.entry('V', 4), Map.entry('W', 4), Map.entry('X', 8),
        Map.entry('Y', 4), Map.entry('Z', 10)
    );

    private static final int DEFAULT_BINGO_BONUS = 50;
    private static final int RACK_SIZE = 7;

    public List<Move> generateMoves(BoardState board, String rack, Ruleset ruleset) {
        return generateMoves(board, rack, ruleset, null);
    }

    public List<Move> generateMoves(BoardState board, String rack, Ruleset ruleset, GameConfig gameConfig) {
        List<Move> moves = new ArrayList<>();
        char[] rackChars = rack.toUpperCase().toCharArray();

        for (Direction direction : Direction.values()) {
            for (int row = 0; row < BoardState.SIZE; row++) {
                for (int col = 0; col < BoardState.SIZE; col++) {
                    if (isAnchor(board, row, col)) {
                        generateMovesFromAnchor(board, row, col, direction, rackChars, ruleset, gameConfig, moves);
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

        if (row == 7 && col == 7 && board.isEmpty()) return true;

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
                                          GameConfig gameConfig, List<Move> results) {
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

        for (int back = 0; back <= maxBack; back++) {
            int startRow = anchorRow - (direction == Direction.DOWN ? back : 0);
            int startCol = anchorCol - (direction == Direction.ACROSS ? back : 0);
            extendWord(board, startRow, startCol, startRow, startCol, direction,
                    new ArrayList<>(toCharList(rack)), "",
                    new ArrayList<>(), ruleset, gameConfig, results);
        }
    }

    private void extendWord(BoardState board, int startRow, int startCol,
                             int row, int col, Direction direction,
                             List<Character> remainingRack, String currentWord,
                             List<Character> tilesUsed, Ruleset ruleset,
                             GameConfig gameConfig, List<Move> results) {
        if (row < 0 || row >= BoardState.SIZE || col < 0 || col >= BoardState.SIZE) {
            if (currentWord.length() >= 2) {
                tryAddMove(board, startRow, startCol, direction, currentWord, tilesUsed, ruleset, gameConfig, results);
            }
            return;
        }

        Cell current = board.getCell(row, col);
        int nextRow = row + (direction == Direction.DOWN ? 1 : 0);
        int nextCol = col + (direction == Direction.ACROSS ? 1 : 0);

        if (current != null && !current.isEmpty()) {
            String nextWord = currentWord + current.getLetter();
            if (wordListEngine.isValidPrefix(nextWord, ruleset)) {
                extendWord(board, startRow, startCol, nextRow, nextCol, direction,
                        remainingRack, nextWord, new ArrayList<>(tilesUsed), ruleset, gameConfig, results);
            }
        } else {
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

                    if (!isCrossWordValid(board, row, col, letter, direction, ruleset)) continue;

                    extendWord(board, startRow, startCol, nextRow, nextCol, direction,
                            newRack, nextWord, newTilesUsed, ruleset, gameConfig, results);

                    // Only submit the word here if the cell immediately after is empty —
                    // if there's an existing tile there, the word continues and "nextWord"
                    // would be incomplete (e.g. submitting "GO" when "GOJ" is the full word).
                    boolean nextCellEmpty = nextRow < 0 || nextRow >= BoardState.SIZE
                            || nextCol < 0 || nextCol >= BoardState.SIZE
                            || board.getCell(nextRow, nextCol) == null
                            || board.getCell(nextRow, nextCol).isEmpty();

                    if (nextWord.length() >= 2 && nextCellEmpty
                            && wordListEngine.isValidWord(nextWord, ruleset)
                            && mustPassThroughAnchor(board, startRow, startCol, row, col, direction)) {
                        tryAddMove(board, startRow, startCol, direction, nextWord, newTilesUsed, ruleset, gameConfig, results);
                    }
                }
            }
        }
    }

    private boolean isCrossWordValid(BoardState board, int row, int col, char letter,
                                      Direction mainDirection, Ruleset ruleset) {
        Direction perp = mainDirection == Direction.ACROSS ? Direction.DOWN : Direction.ACROSS;
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
                             GameConfig gameConfig, List<Move> results) {
        if (!wordListEngine.isValidWord(word, ruleset)) return;

        int score = calculateScore(board, startRow, startCol, direction, word, tilesUsed.size(), gameConfig);
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
                                Direction direction, String word, int tilesFromRack,
                                GameConfig gameConfig) {
        int score = 0;
        int wordMultiplier = 1;

        for (int i = 0; i < word.length(); i++) {
            int row = startRow + (direction == Direction.DOWN ? i : 0);
            int col = startCol + (direction == Direction.ACROSS ? i : 0);
            Cell cell = board.getCell(row, col);

            char letter = word.charAt(i);
            int letterValue = letterValue(letter, gameConfig);

            if (cell != null && cell.isEmpty()) {
                // New tile — use the square type read from the image
                SquareType squareType = cell.getSquareType() != null ? cell.getSquareType() : SquareType.STANDARD;
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
            int bingoBonus = (gameConfig != null && gameConfig.getBingoBonus() != null)
                    ? gameConfig.getBingoBonus() : DEFAULT_BINGO_BONUS;
            score += bingoBonus;
        }

        return score;
    }

    private int letterValue(char c, GameConfig gameConfig) {
        char upper = Character.toUpperCase(c);
        if (gameConfig != null && gameConfig.getLetterValues() != null) {
            return gameConfig.getLetterValues().getOrDefault(String.valueOf(upper), 0);
        }
        return DEFAULT_LETTER_VALUES.getOrDefault(upper, 0);
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
