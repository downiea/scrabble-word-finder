import React, { useState, useCallback, useEffect } from 'react'
import BoardUpload from './components/BoardUpload'
import BoardGrid from './components/BoardGrid'
import GameSelector from './components/GameSelector'
import TileInput from './components/TileInput'
import RulesetSelector from './components/RulesetSelector'
import SuggestionList from './components/SuggestionList'
import CropEditor from './components/CropEditor'
import { fetchGameConfigs, extractBoard, analyseBoard } from './services/api'

const SIZE = 15

function emptyGrid() {
  return Array.from({ length: SIZE }, () => Array(SIZE).fill(null))
}

function emptySquareTypes() {
  return Array.from({ length: SIZE }, () => Array(SIZE).fill('STANDARD'))
}

function squareTypesFromConfig(config) {
  const sq = emptySquareTypes()
  if (!config?.boardLayout?.length) return sq
  for (const entry of config.boardLayout) {
    sq[entry.row][entry.col] = entry.squareType
  }
  return sq
}

function gridsFromBoardState(boardState) {
  const letters = emptyGrid()
  const squareTypes = emptySquareTypes()
  if (!boardState?.grid) return { letters, squareTypes }
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      const cell = boardState.grid[r]?.[c]
      if (!cell) continue
      if (cell.letter) letters[r][c] = cell.letter
      if (cell.squareType) squareTypes[r][c] = cell.squareType
    }
  }
  return { letters, squareTypes }
}

function occupiedCells(grid, squareTypes) {
  const cells = []
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      cells.push({
        row: r,
        col: c,
        letter: grid[r][c] ?? null,
        squareType: squareTypes[r][c] ?? 'STANDARD',
      })
    }
  }
  return cells
}

export default function App() {
  const [step, setStep] = useState(1)
  const [gameConfigs, setGameConfigs] = useState([])
  const [gameConfigId, setGameConfigId] = useState('nyt_crossplay')
  const [imageType, setImageType] = useState('DIGITAL')
  const [extracting, setExtracting] = useState(false)
  const [extractError, setExtractError] = useState(null)
  const [warnings, setWarnings] = useState([])

  const [grid, setGrid] = useState(emptyGrid())
  const [squareTypes, setSquareTypes] = useState(emptySquareTypes())
  const [tiles, setTiles] = useState('')
  const [ruleset, setRuleset] = useState('US')
  const [selectedCell, setSelectedCell] = useState(null)

  const [imagePreviewUrl, setImagePreviewUrl] = useState(null)
  const [showCropEditor, setShowCropEditor] = useState(false)

  const [analysing, setAnalysing] = useState(false)
  const [analyseError, setAnalyseError] = useState(null)
  const [suggestions, setSuggestions] = useState(null)

  useEffect(() => {
    fetchGameConfigs()
      .then(configs => {
        setGameConfigs(configs)
        const defaultConfig = configs.find(c => c.id === gameConfigId)
        if (defaultConfig) setSquareTypes(squareTypesFromConfig(defaultConfig))
      })
      .catch(() => {})
  }, [])

  async function handleExtract(file) {
    setExtractError(null)
    setExtracting(true)
    setGrid(emptyGrid())
    setSquareTypes(emptySquareTypes())
    setTiles('')
    setWarnings([])
    setShowCropEditor(false)
    if (imagePreviewUrl) URL.revokeObjectURL(imagePreviewUrl)
    setImagePreviewUrl(URL.createObjectURL(file))

    try {
      const result = await extractBoard(file, imageType, gameConfigId)
      const { letters, squareTypes: sq } = gridsFromBoardState(result.boardState)
      setGrid(letters)
      // Backend overlays config layout server-side, so always use what it returns
      setSquareTypes(sq)
      if (result.extractedTiles) setTiles(result.extractedTiles)
      if (result.warnings?.length) setWarnings(result.warnings)
    } catch (err) {
      setExtractError(err.message)
    } finally {
      setExtracting(false)
    }
  }

  const handleCellClick = useCallback((row, col) => {
    setSelectedCell(prev =>
      prev?.row === row && prev?.col === col ? null : { row, col }
    )
  }, [])

  const handleKeyDown = useCallback((e) => {
    if (!selectedCell) return
    const { row, col } = selectedCell

    if (e.key === 'Backspace' || e.key === 'Delete') {
      e.preventDefault()
      setGrid(prev => {
        const next = prev.map(r => [...r])
        next[row][col] = null
        return next
      })
    } else if (/^[a-zA-Z]$/.test(e.key)) {
      e.preventDefault()
      const letter = e.key.toUpperCase()
      setGrid(prev => {
        const next = prev.map(r => [...r])
        next[row][col] = letter
        return next
      })
      if (col < SIZE - 1) setSelectedCell({ row, col: col + 1 })
      else if (row < SIZE - 1) setSelectedCell({ row: row + 1, col: 0 })
      else setSelectedCell(null)
    } else if (e.key === 'Escape') {
      setSelectedCell(null)
    }
  }, [selectedCell])

  async function handleAnalyse() {
    if (!tiles || tiles.length < 1) { setAnalyseError('Please enter your tiles.'); return }
    setAnalyseError(null)
    setAnalysing(true)
    setSuggestions(null)

    try {
      const result = await analyseBoard(occupiedCells(grid, squareTypes), tiles, ruleset, gameConfigId)
      setSuggestions(result.suggestions)
      setStep(2)
    } catch (err) {
      setAnalyseError(err.message)
    } finally {
      setAnalysing(false)
    }
  }

  function handleCropsSaved(boardCrop, tilesCrop) {
    setGameConfigs(prev => prev.map(c =>
      c.id === gameConfigId ? { ...c, boardCrop, tilesCrop } : c
    ))
    setShowCropEditor(false)
  }

  function handleBack() {
    setStep(1)
    setSuggestions(null)
    setAnalyseError(null)
  }

  return (
    <div className="app">
      <header className="app-header">
        <h1>Scrabble Word Finder</h1>
        <p>Upload your board, confirm the tiles, then get the best moves.</p>
      </header>

      <div className="step-indicator">
        <div className={`step-dot ${step >= 1 ? 'active' : ''}`}>1</div>
        <div className="step-line" />
        <div className={`step-dot ${step >= 2 ? 'active' : ''}`}>2</div>
      </div>

      {step === 1 && (
        <main className="app-main two-col">
          <section className="input-panel">
            <GameSelector configs={gameConfigs} value={gameConfigId} onChange={id => {
            setGameConfigId(id)
            const config = gameConfigs.find(c => c.id === id)
            if (config) setSquareTypes(squareTypesFromConfig(config))
          }} />
            <RulesetSelector value={ruleset} onChange={setRuleset} />
            <BoardUpload
              onImageSelected={handleExtract}
              onImageTypeChange={setImageType}
            />

            {imagePreviewUrl && (
              <div className="crop-section">
                <button
                  type="button"
                  className="crop-toggle-btn"
                  onClick={() => setShowCropEditor(v => !v)}
                >
                  {showCropEditor ? '▲ Hide crop editor' : '✂ Configure crop regions'}
                </button>
                {showCropEditor && (() => {
                  const cfg = gameConfigs.find(c => c.id === gameConfigId)
                  return (
                    <CropEditor
                      imageUrl={imagePreviewUrl}
                      gameConfigId={gameConfigId}
                      initialBoardCrop={cfg?.boardCrop ?? null}
                      initialTilesCrop={cfg?.tilesCrop ?? null}
                      onSaved={handleCropsSaved}
                    />
                  )
                })()}
              </div>
            )}

            {extracting && (
              <div className="inline-loading">
                <div className="spinner-sm" />
                <span>Reading board with Claude Vision…</span>
              </div>
            )}
            {extractError && <p className="error-message">{extractError}</p>}

            <TileInput value={tiles} onChange={setTiles} />

            {warnings.length > 0 && (
              <div className="warnings">
                <p className="warnings-title">Low-confidence reads — check these cells:</p>
                <ul>{warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
              </div>
            )}

            <p className="board-hint">
              Click a cell to select it, then type a letter or press Delete to clear.
            </p>

            <button className="analyse-btn" onClick={handleAnalyse} disabled={analysing}>
              {analysing ? 'Analysing…' : 'Find Best Moves'}
            </button>

            {analyseError && <p className="error-message">{analyseError}</p>}
          </section>

          <section className="board-panel">
            <BoardGrid
              cells={grid}
              squareTypes={squareTypes}
              selectedCell={selectedCell}
              onCellClick={handleCellClick}
              onKeyDown={handleKeyDown}
            />
          </section>
        </main>
      )}

      {step === 2 && (
        <main className="app-main two-col">
          <section className="board-panel">
            <BoardGrid
              cells={grid}
              squareTypes={squareTypes}
              highlightMove={suggestions?.[0]}
              readOnly
            />
          </section>

          <section className="results-panel">
            <button className="back-btn" onClick={handleBack}>← Back</button>
            {tiles && (
              <div className="rack-display">
                <span className="rack-label">Your tiles</span>
                <div className="tile-display">
                  {tiles.split('').map((letter, i) => (
                    <span key={i} className="tile">
                      {letter === '_' ? <em>blank</em> : letter}
                    </span>
                  ))}
                </div>
              </div>
            )}
            {suggestions && <SuggestionList suggestions={suggestions} />}
          </section>
        </main>
      )}
    </div>
  )
}
