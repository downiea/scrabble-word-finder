package com.scrabble.service;

import com.scrabble.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MoveGenerator using a stub WordListEngine.
 *
 * These tests do not load Spring context or word list files — they use a
 * minimal in-memory word set to verify move legality rules precisely.
 */
class MoveGeneratorTest {

    private MoveGenerator moveGenerator;

    // Minimal word set used across tests
    private static final Set<String> VALID_WORDS = Set.of(
            "GO", "SO", "TO", "DO", "NO", "JO",
            "JOG", "GOD", "SOD", "NOD",
            "STAR", "RATS", "ARTS", "TARS",
            "AT", "AR", "AS", "TA"
    );

    @BeforeEach
    void setUp() {
        WordListEngine stubEngine = new WordListEngine() {
            @Override
            public boolean isValidWord(String word, Ruleset ruleset) {
                return VALID_WORDS.contains(word.toUpperCase());
            }

            @Override
            public boolean isValidPrefix(String prefix, Ruleset ruleset) {
                String upper = prefix.toUpperCase();
                return VALID_WORDS.stream().anyMatch(w -> w.startsWith(upper));
            }
        };
        moveGenerator = new MoveGenerator(stubEngine);
    }

    private BoardState emptyBoard() {
        BoardState board = new BoardState();
        for (int r = 0; r < BoardState.SIZE; r++) {
            for (int c = 0; c < BoardState.SIZE; c++) {
                board.setCell(Cell.builder().row(r).col(c).squareType(SquareType.STANDARD).build());
            }
        }
        return board;
    }

    private void placeLetters(BoardState board, int row, int startCol, String word) {
        for (int i = 0; i < word.length(); i++) {
            Cell cell = board.getCell(row, startCol + i);
            cell.setLetter(word.charAt(i));
        }
    }

    private void placeLettersDown(BoardState board, int startRow, int col, String word) {
        for (int i = 0; i < word.length(); i++) {
            Cell cell = board.getCell(startRow + i, col);
            cell.setLetter(word.charAt(i));
        }
    }

    @Test
    @DisplayName("Does not suggest a word that would form an invalid cross-word with an adjacent tile")
    void doesNotSuggestWordThatCreatesInvalidCrossWord() {
        // Board has J at (7, 9). Rack has G, O.
        // Placing GO at (7, 7)-(7, 8) ACROSS would form GOJ — not in word list.
        BoardState board = emptyBoard();
        board.getCell(7, 9).setLetter('J');

        List<Move> moves = moveGenerator.generateMoves(board, "GO", Ruleset.US);

        // No move should produce the word GO ending immediately before J (which would make GOJ)
        boolean hasIllegalGo = moves.stream().anyMatch(m ->
                m.getWord().equals("GO")
                && m.getDirection() == Direction.ACROSS
                && m.getStartRow() == 7
                && m.getStartCol() == 7   // O would be at col 8, J at col 9 → forms GOJ
        );
        assertThat(hasIllegalGo).isFalse();
    }

    @Test
    @DisplayName("Does suggest a word that ends with no tile after it")
    void suggestsWordWithNoTileAfter() {
        // S is to the LEFT of (7,7), making (7,7) an anchor.
        // Cross-words for G and O are empty in the DOWN direction → GO is fully legal.
        BoardState board = emptyBoard();
        board.getCell(7, 6).setLetter('S');

        List<Move> moves = moveGenerator.generateMoves(board, "GO", Ruleset.US);

        boolean hasLegalGo = moves.stream().anyMatch(m ->
                m.getWord().equals("GO")
                && m.getDirection() == Direction.ACROSS
                && m.getStartRow() == 7
                && m.getStartCol() == 7
        );
        assertThat(hasLegalGo).isTrue();
    }

    @Test
    @DisplayName("Suggests word that incorporates an existing board tile")
    void incorporatesExistingBoardTile() {
        // Board has O at (7,8). Rack has G, D.
        // G placed at (7,7), D at (7,9) → GOD (incorporating existing O).
        BoardState board = emptyBoard();
        board.getCell(7, 8).setLetter('O');

        List<Move> moves = moveGenerator.generateMoves(board, "GD", Ruleset.US);

        boolean hasGod = moves.stream().anyMatch(m ->
                m.getWord().equals("GOD") && m.getDirection() == Direction.ACROSS
        );
        assertThat(hasGod).isTrue();
    }

    @Test
    @DisplayName("Does not suggest a word extending into an existing tile that makes an invalid word")
    void doesNotExtendIntoInvalidWord() {
        // Board has J at (7,7). Rack has G, O.
        // Placing GO before J: G at (7,5), O at (7,6) → GOJ — invalid.
        BoardState board = emptyBoard();
        board.getCell(7, 7).setLetter('J');

        List<Move> moves = moveGenerator.generateMoves(board, "GO", Ruleset.US);

        boolean hasGoj = moves.stream().anyMatch(m -> m.getWord().equals("GOJ"));
        assertThat(hasGoj).isFalse();
    }

    @Test
    @DisplayName("Only valid cross-words are allowed when placing tiles")
    void rejectsMovesWithInvalidCrossWords() {
        // Board has J at (6, 7). Rack has G, O.
        // Placing G at (7, 7) ACROSS — cross-word would be GJ (up) — not valid.
        BoardState board = emptyBoard();
        board.getCell(6, 7).setLetter('J');
        // Need an anchor — place a tile to the left
        board.getCell(7, 6).setLetter('S');

        List<Move> moves = moveGenerator.generateMoves(board, "GO", Ruleset.US);

        // G at (7,7) would form cross-word GJ vertically — invalid, so this specific placement
        // of GO starting at (7,7) ACROSS should not appear
        boolean hasIllegalPlacement = moves.stream().anyMatch(m ->
                m.getWord().equals("GO")
                && m.getDirection() == Direction.ACROSS
                && m.getStartRow() == 7
                && m.getStartCol() == 7
        );
        assertThat(hasIllegalPlacement).isFalse();
    }
}
