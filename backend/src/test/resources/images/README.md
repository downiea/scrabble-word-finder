# Test images

Place real game screenshots here to use as the basis for vision extraction tests.

Naming convention: `{gameConfigId}_sample.png` (or .jpg)

Examples:
- `nyt_crossplay_sample.png`
- `scrabble_go_sample.png`

These images are committed to git so tests are reproducible across machines.

## Workflow

1. Add a screenshot here (e.g. `nyt_crossplay_sample.png`)
2. Note the expected board state — which letters are in which cells (row 0-14, col 0-14)
3. Run the capture test once to save the raw Claude response as a fixture:
   ```
   ANTHROPIC_API_KEY=sk-... ./gradlew test --tests "*CaptureFixture*" -Dtests.capture=true
   ```
4. The fixture JSON is saved to `src/test/resources/fixtures/`
5. Update the expected cell assertions in `VisionExtractionTest`
6. From then on, `VisionExtractionTest` runs offline using the fixture — no API calls
