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

  // Image state — file selection is separate from extraction
  const [imageFile, setImageFile] = useState(null)
  const [previewUrl, setPreviewUrl] = useState(null)
  const [boardExtracted, setBoardExtracted] = useState(false)

  const [extracting, setExtracting] = useState(false)
  const [extractError, setExtractError] = useState(null)
  const [warnings, setWarnings] = useState([])

  const [grid, setGrid] = useState(emptyGrid())
  const [squareTypes, setSquareTypes] = useState(emptySquareTypes())
  const [tiles, setTiles] = useState('')
  const [ruleset, setRuleset] = useState('US')
  const [selectedCell, setSelectedCell] = useState(null)

  const [analysing, setAnalysing] = useState(false)
  const [analyseError, setAnalyseError] = useState(null)
  const [suggestions, setSuggestions] = useState(null)
  const [selectedSuggestion, setSelectedSuggestion] = useState(null)

  const [devMode, setDevMode] = useState(() => localStorage.getItem('devMode') === 'true')
  const [debugEnhancedImage, setDebugEnhancedImage] = useState(null)
  const [showDebugImage, setShowDebugImage] = useState(false)

  function toggleDevMode() {
    setDevMode(prev => {
      const next = !prev
      localStorage.setItem('devMode', next)
      return next
    })
  }

  useEffect(() => {
    fetchGameConfigs()
      .then(configs => {
        setGameConfigs(configs)
        const defaultConfig = configs.find(c => c.id === gameConfigId)
        if (defaultConfig) setSquareTypes(squareTypesFromConfig(defaultConfig))
      })
      .catch(() => {})
  }, [])

  // Step 1a — image selected, no API call yet
  function handleFileSelected(file, url) {
    if (previewUrl) URL.revokeObjectURL(previewUrl)
    setPreviewUrl(url)
    setImageFile(file)
    setBoardExtracted(false)
    setGrid(emptyGrid())
    setTiles('')
    setWarnings([])
    setExtractError(null)
    setSelectedCell(null)
    const config = gameConfigs.find(c => c.id === gameConfigId)
    if (config) setSquareTypes(squareTypesFromConfig(config))
  }

  // Step 1b — send image to Claude Vision
  async function handleExtract() {
    if (!imageFile) return
    setExtractError(null)
    setExtracting(true)
    setGrid(emptyGrid())
    setWarnings([])

    try {
      const result = await extractBoard(imageFile, imageType, gameConfigId, devMode)
      const { letters, squareTypes: sq } = gridsFromBoardState(result.boardState)
      setGrid(letters)
      setSquareTypes(sq)
      if (result.extractedTiles) setTiles(result.extractedTiles)
      if (result.warnings?.length) setWarnings(result.warnings)
      if (result.debugEnhancedImageBase64) {
        setDebugEnhancedImage(result.debugEnhancedImageBase64)
        setShowDebugImage(true)
      } else {
        setDebugEnhancedImage(null)
      }
      setBoardExtracted(true)
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

  // Step 2 — move analysis
  async function handleAnalyse() {
    if (!tiles || tiles.length < 1) { setAnalyseError('Please enter your tiles.'); return }
    setAnalyseError(null)
    setAnalysing(true)
    setSuggestions(null)

    try {
      const result = await analyseBoard(occupiedCells(grid, squareTypes), tiles, ruleset, gameConfigId)
      setSuggestions(result.suggestions)
      setSelectedSuggestion(result.suggestions?.[0] ?? null)
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
  }

  function handleBack() {
    setStep(1)
    setSuggestions(null)
    setAnalyseError(null)
  }

  // Current game config
  const currentConfig = gameConfigs.find(c => c.id === gameConfigId)
  const hasSavedCrop = !!(currentConfig?.boardCrop || currentConfig?.tilesCrop)

  return (
    <div className="app">
      <header className="app-header">
        <h1>Scrabble Word Finder</h1>
        <p>Upload your board, confirm the tiles, then get the best moves.</p>
        <button className="dev-mode-toggle" onClick={toggleDevMode} title="Toggle developer mode">
          {devMode ? '🔬 Dev mode on' : '🔬'}
        </button>
      </header>

      <div className="step-indicator">
        <div className={`step-dot ${step >= 1 ? 'active' : ''}`}>1</div>
        <div className="step-line" />
        <div className={`step-dot ${step >= 2 ? 'active' : ''}`}>2</div>
      </div>

      {step === 1 && (
        <main className="app-main two-col">
          {/* ── Left panel ── */}
          <section className="input-panel">
            <GameSelector
              configs={gameConfigs}
              value={gameConfigId}
              onChange={id => {
                setGameConfigId(id)
                const config = gameConfigs.find(c => c.id === id)
                if (config) setSquareTypes(squareTypesFromConfig(config))
              }}
            />
            <RulesetSelector value={ruleset} onChange={setRuleset} />

            <BoardUpload
              onImageSelected={handleFileSelected}
              onImageTypeChange={setImageType}
            />

            {/* Crop status — shown once an image is loaded */}
            {imageFile && !boardExtracted && (
              <div className="crop-status">
                {hasSavedCrop
                  ? <span className="crop-status-indicator configured">✓ Crop regions configured</span>
                  : <span className="crop-status-indicator unconfigured">No crop regions set</span>
                }
                <span className="crop-status-hint">
                  {hasSavedCrop
                    ? 'Adjust the regions on the right if needed, then extract.'
                    : 'Optionally draw crop regions on the image to improve accuracy.'}
                </span>
              </div>
            )}

            {/* Extract button — shown once image loaded, before extraction */}
            {imageFile && !boardExtracted && (
              <>
                <button
                  className="extract-btn"
                  onClick={handleExtract}
                  disabled={extracting}
                >
                  {extracting ? (
                    <><span className="btn-spinner" /> Reading board…</>
                  ) : (
                    'Extract Board'
                  )}
                </button>
                {extractError && <p className="error-message">{extractError}</p>}
              </>
            )}

            {/* Debug: enhanced image panel */}
            {devMode && boardExtracted && debugEnhancedImage && (
              <div className="debug-panel">
                <button className="debug-panel-toggle" onClick={() => setShowDebugImage(v => !v)}>
                  {showDebugImage ? '▲ Hide enhanced image' : '▼ Show enhanced image sent to vision API'}
                </button>
                {showDebugImage && (
                  <img
                    src={`data:image/png;base64,${debugEnhancedImage}`}
                    alt="Enhanced image sent to vision API"
                    className="debug-enhanced-img"
                  />
                )}
              </div>
            )}

            {/* Post-extraction controls */}
            {boardExtracted && (
              <>
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

                <button
                  className="reextract-btn"
                  onClick={() => { setBoardExtracted(false); setGrid(emptyGrid()); setTiles('') }}
                >
                  ↩ Re-extract image
                </button>
              </>
            )}
          </section>

          {/* ── Right panel ── */}
          <section className="board-panel">
            {imageFile && !boardExtracted ? (
              <CropEditor
                key={gameConfigId}
                imageUrl={previewUrl}
                gameConfigId={gameConfigId}
                initialBoardCrop={currentConfig?.boardCrop ?? null}
                initialTilesCrop={currentConfig?.tilesCrop ?? null}
                onSaved={handleCropsSaved}
              />
            ) : (
              <BoardGrid
                cells={grid}
                squareTypes={squareTypes}
                selectedCell={selectedCell}
                onCellClick={handleCellClick}
                onKeyDown={handleKeyDown}
              />
            )}
          </section>
        </main>
      )}

      {step === 2 && (
        <main className="app-main two-col">
          <section className="board-panel">
            <BoardGrid
              cells={grid}
              squareTypes={squareTypes}
              highlightMove={selectedSuggestion}
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
            {suggestions && (
              <SuggestionList
                suggestions={suggestions}
                onSelect={setSelectedSuggestion}
                selectedRank={selectedSuggestion?.rank}
              />
            )}
          </section>
        </main>
      )}
    </div>
  )
}
