# Scrabble Word Finder

Photograph your Scrabble board, enter your tiles, and get the top 5 move suggestions — ranked by both raw score and strategic value.

Uses **Claude Vision API** to read the board image and a full Scrabble move engine for both US (TWL06) and UK (Collins) rulesets.

---

## Quick Start

### Prerequisites

Install these tools first (all via Homebrew):

```bash
brew install openjdk@21
brew install gh
```

Set your Anthropic API key:

```bash
export ANTHROPIC_API_KEY=your_key_here
```

### Word Lists

The word list files are not included (copyright). See [backend/src/main/resources/words/README.md](backend/src/main/resources/words/README.md) for instructions.

---

### Run the backend

```bash
cd backend
./gradlew bootRun
```

> First run: if `./gradlew` is not executable, run `gradle wrapper` (requires Gradle installed) or `chmod +x gradlew`.

The API starts at http://localhost:8080

### Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:3000

---

## Architecture

```
scrabble-word-finder/
├── backend/          Java 21 / Spring Boot / Gradle
│   └── src/main/java/com/scrabble/
│       ├── controller/    REST API endpoint
│       ├── service/       Board vision, move generation, ranking
│       ├── strategy/      Pluggable strategic scoring factors
│       └── model/         Domain objects
└── frontend/         React / Vite
    └── src/
        ├── components/    BoardUpload, TileInput, RulesetSelector, SuggestionList
        └── services/      API client
```

### API

```
POST /api/analyse   (multipart/form-data)
  image    — board photo (JPEG/PNG)
  tiles    — rack letters, e.g. AEINRST  (use _ for blank)
  ruleset  — US or UK
```

Returns top 5 moves with raw score, strategy score, and plain-English reasons.

---

## Adding / Removing Strategic Factors

Each strategic factor is a Spring `@Component` implementing `StrategyFactor`:

```java
@Component
public class MyNewFactor implements StrategyFactor {
    @Override
    public StrategyResult evaluate(Move move, BoardState boardState) {
        // return StrategyResult.of(delta, "reason") or StrategyResult.neutral()
    }
}
```

Add the class → it is automatically included. Remove `@Component` or delete the class → it is excluded. No changes needed elsewhere.

Current factors:
- **BlockOpponentPremiumFactor** — rewards landing on TW/DW squares; penalises leaving them open
- **AvoidOpeningLaneFactor** — penalises creating clear paths to premium squares for the opponent
