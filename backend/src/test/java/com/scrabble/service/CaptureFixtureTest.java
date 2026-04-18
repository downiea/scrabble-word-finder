package com.scrabble.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.GameConfig;
import com.scrabble.model.VisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Manual capture test — run this ONCE with a real image to save a fixture JSON.
 *
 * NOT included in normal test runs (@Disabled). Remove @Disabled and run with:
 *   ANTHROPIC_API_KEY=sk-... ./gradlew test --tests "*CaptureFixture*"
 *
 * The raw Claude response is saved to src/test/resources/fixtures/{configId}_sample.json.
 * Commit that file, then fill in expected cell assertions in VisionExtractionTest.
 */
@SpringBootTest
class CaptureFixtureTest {

    @Autowired
    BoardVisionService boardVisionService;

    @Autowired
    GameConfigService gameConfigService;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * Change GAME_CONFIG_ID and IMAGE_FILENAME to match the image you are capturing.
     */
    private static final String GAME_CONFIG_ID = "nyt_crossplay";
    private static final String IMAGE_FILENAME = "nyt_crossplay_sample.jpeg";
    private static final String IMAGE_TYPE     = "DIGITAL";

    @Test
    void captureFixture() throws Exception {
        assumeThat(System.getenv("ANTHROPIC_API_KEY"))
                .as("ANTHROPIC_API_KEY must be set to run capture tests")
                .isNotNull().isNotBlank();

        // Load test image
        byte[] imageBytes;
        try (InputStream is = getClass().getResourceAsStream("/images/" + IMAGE_FILENAME)) {
            assumeThat(is)
                    .as("Test image not found: src/test/resources/images/" + IMAGE_FILENAME)
                    .isNotNull();
            imageBytes = is.readAllBytes();
        }

        String contentType = IMAGE_FILENAME.endsWith(".png") ? "image/png" : "image/jpeg";
        MockMultipartFile file = new MockMultipartFile("image", IMAGE_FILENAME, contentType, imageBytes);

        GameConfig config = gameConfigService.getById(GAME_CONFIG_ID);

        // --- Make the real Claude API call ---
        System.out.println("Calling Claude with image: " + IMAGE_FILENAME);
        VisionResult result = boardVisionService.extractBoardState(file, IMAGE_TYPE, config);

        // Print parsed board state so you can verify positions and fill in assertions
        System.out.println("\n=== PARSED BOARD STATE ===");
        printBoard(result);
        System.out.println("=== EXTRACTED TILES: " + result.getExtractedTiles() + " ===");
        if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
            System.out.println("=== WARNINGS: " + result.getWarnings() + " ===");
        }

        // Save the raw fixture — the captured response was already used by extractBoardState,
        // so we serialise the VisionResult as the fixture for replay tests.
        String fixtureName = GAME_CONFIG_ID + "_sample.json";
        Path fixturesDir = resolveProjectPath("src/test/resources/fixtures");
        Files.createDirectories(fixturesDir);
        Path fixturePath = fixturesDir.resolve(fixtureName);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fixturePath.toFile(), result);
        System.out.println("\nFixture saved to: " + fixturePath.toAbsolutePath());
        System.out.println("Now fill in the expected cell assertions in VisionExtractionTest.");
    }

    private void printBoard(VisionResult result) {
        var grid = result.getBoardState().getGrid();
        String cols = "   A  B  C  D  E  F  G  H  I  J  K  L  M  N  O";
        System.out.println(cols);
        for (int r = 0; r < 15; r++) {
            StringBuilder row = new StringBuilder(String.format("%2d ", r + 1));
            for (int c = 0; c < 15; c++) {
                var cell = grid[r][c];
                char ch = (cell != null && cell.getLetter() != null) ? cell.getLetter() : '.';
                row.append(' ').append(ch).append(' ');
            }
            System.out.println(row);
        }
    }

    /** Resolve a path relative to the backend project root, regardless of working directory. */
    private Path resolveProjectPath(String relative) {
        // When Gradle runs tests, the working directory is the project root (backend/)
        Path fromCwd = Paths.get(relative);
        if (fromCwd.toFile().getParentFile().exists()) return fromCwd;
        // Fallback: walk up from the class location
        Path classPath = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
        return classPath.getParent().getParent().getParent().resolve(relative);
    }
}
