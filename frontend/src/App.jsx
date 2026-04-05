import React, { useState } from 'react'
import BoardUpload from './components/BoardUpload'
import TileInput from './components/TileInput'
import RulesetSelector from './components/RulesetSelector'
import SuggestionList from './components/SuggestionList'
import { analyseBoard } from './services/api'

export default function App() {
  const [image, setImage] = useState(null)
  const [tiles, setTiles] = useState('')
  const [ruleset, setRuleset] = useState('UK')
  const [suggestions, setSuggestions] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  async function handleAnalyse() {
    if (!image) { setError('Please upload a board image.'); return }
    if (!tiles || tiles.length < 1) { setError('Please enter your tiles.'); return }

    setError(null)
    setLoading(true)
    setSuggestions(null)

    try {
      const result = await analyseBoard(image, tiles, ruleset)
      setSuggestions(result.suggestions)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="app">
      <header className="app-header">
        <h1>Scrabble Word Finder</h1>
        <p>Upload a photo of your board, enter your tiles, and get the best moves.</p>
      </header>

      <main className="app-main">
        <section className="input-panel">
          <RulesetSelector value={ruleset} onChange={setRuleset} />
          <BoardUpload onImageSelected={setImage} />
          <TileInput value={tiles} onChange={setTiles} />

          <button
            className="analyse-btn"
            onClick={handleAnalyse}
            disabled={loading}
          >
            {loading ? 'Analysing...' : 'Find Best Moves'}
          </button>

          {error && <p className="error-message">{error}</p>}
        </section>

        <section className="results-panel">
          {loading && (
            <div className="loading">
              <div className="spinner" />
              <p>Analysing your board with Claude Vision...</p>
            </div>
          )}
          {suggestions && <SuggestionList suggestions={suggestions} />}
        </section>
      </main>
    </div>
  )
}
