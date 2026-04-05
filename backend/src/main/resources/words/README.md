# Word Lists

The application requires two word list files that are not included in this repository due to copyright restrictions.

## Required files

Place the following files in this directory (`src/main/resources/words/`):

| File | Ruleset | Description |
|------|---------|-------------|
| `twl06.txt` | US | Tournament Word List 2006 |
| `collins.txt` | UK | Collins Scrabble Words |

Each file must contain **one uppercase word per line**, e.g.:

```
AA
AAH
AAHED
...
```

## Acquiring the word lists

### TWL06 (US)
The TWL06 word list is available from various Scrabble resources online.
A commonly referenced public-domain-compatible list is the ENABLE word list:
https://www.wordgamedictionary.com/twl06/

### Collins Scrabble Words (UK)
Collins Scrabble Words (CSW/SOWPODS) is available from:
https://www.wordgamedictionary.com/sowpods/

### Format conversion
If your source file has lowercase words, convert with:
```bash
tr '[:lower:]' '[:upper:]' < source.txt > twl06.txt
```

## Running without word lists
The application will start without the word lists but will log a warning and return no valid moves.
This is useful for testing the board vision and API pipeline in isolation.
