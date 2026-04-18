package com.scrabble.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.BoardState;
import com.scrabble.model.VisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline regression tests for vision extraction using pre-captured Claude response fixtures.
 *
 * These tests NEVER call the Claude API — they load a saved VisionResult from
 * src/test/resources/fixtures/ and assert specific cell positions.
 *
 * To add a new test:
 *   1. Add a real screenshot to src/test/resources/images/{configId}_sample.png
 *   2. Run CaptureFixtureTest once to generate the fixture JSON (saves a VisionResult)
 *   3. Look at the printed board output, confirm which cells should have which letters
 *   4. Copy the test method below and fill in the expected positions
 */
@SpringBootTest(properties = "anthropic.api.key=test-not-used")
class VisionExtractionTest {

    @Autowired
    ObjectMapper objectMapper;

    // ── NYT Crossplay ────────────────────────────────────────────────────────

    @Test
    void nytCrossplay_fixturePositionsAreCorrect() throws Exception {
        VisionResult result = loadFixture("nyt_crossplay_sample.json");

        BoardState board = result.getBoardState();

        // -----------------------------------------------------------------------
        // TODO: Fill in the expected cell positions after running CaptureFixtureTest.
        //
        // Read the board printout from CaptureFixtureTest output, then add
        // assertions like:
        //
        //   assertCell(board, 7, 7, 'S');   // centre star square
        //   assertCell(board, 6, 7, 'H');
        //   assertCell(board, 6, 8, 'E');
        //   assertCell(board, 6, 9, 'L');
        //   assertCell(board, 6, 10, 'L');
        //   assertCell(board, 6, 11, 'O');
        //
        // Use (row 0-14, col 0-14) — i.e. row 1 on the board = row 0 here.
        //
        // Rows and cols that should be EMPTY can also be asserted:
        //   assertEmpty(board, 0, 0);
        // -----------------------------------------------------------------------

        // Tiles
        // assertThat(result.getExtractedTiles()).isNotNull();

        // Placeholder assertion so the test is valid until filled in
        assertThat(board).isNotNull();
    }


    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Loads a fixture from src/test/resources/fixtures/.
     * If the fixture file doesn't exist, the test is skipped (not failed).
     */
    private VisionResult loadFixture(String filename) throws Exception {
        String path = "/fixtures/" + filename;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assumeThat(is)
                    .as("Fixture not found: " + path + " — run CaptureFixtureTest first")
                    .isNotNull();
            return objectMapper.readValue(is, VisionResult.class);
        }
    }

    private void assertCell(BoardState board, int row, int col, char expectedLetter) {
        var cell = board.getCell(row, col);
        assertThat(cell).as("Cell (%d,%d) should exist", row, col).isNotNull();
        assertThat(cell.getLetter())
                .as("Cell (%d,%d) — row %d col %s", row, col, row + 1, colLabel(col))
                .isEqualTo(expectedLetter);
    }

    private void assertEmpty(BoardState board, int row, int col) {
        var cell = board.getCell(row, col);
        if (cell != null) {
            assertThat(cell.getLetter())
                    .as("Cell (%d,%d) — row %d col %s should be empty", row, col, row + 1, colLabel(col))
                    .isNull();
        }
    }

    private String colLabel(int col) {
        return String.valueOf((char) ('A' + col));
    }
}
