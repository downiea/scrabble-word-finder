import React from 'react'

const COL_LABELS = 'ABCDEFGHIJKLMNO'

const RANK_LABELS = {
  1: 'Best Move',
  2: '2nd Choice',
  3: '3rd Choice',
  4: '4th Choice',
  5: '5th Choice',
}

export default function SuggestionList({ suggestions, onSelect, selectedRank }) {
  if (!suggestions || suggestions.length === 0) {
    return <p className="no-suggestions">No valid moves found. Check your tiles and try again.</p>
  }

  return (
    <div className="suggestion-list">
      <h2>Top Suggestions</h2>
      {suggestions.map((s) => (
        <div
          key={s.rank}
          className={`suggestion-card rank-${s.rank}${s.rank === selectedRank ? ' selected' : ''}`}
          onClick={() => onSelect?.(s)}
          style={{ cursor: onSelect ? 'pointer' : undefined }}
        >
          <div className="suggestion-header">
            <span className="rank-label">{RANK_LABELS[s.rank] || `#${s.rank}`}</span>
            <span className="word">{s.word}</span>
            <span className="total-score">{s.totalScore} pts</span>
          </div>
          <div className="suggestion-detail">
            <span className="placement">
              {s.direction === 'ACROSS' ? 'Across' : 'Down'} from {COL_LABELS[s.startCol]}{s.startRow + 1}
            </span>
            <span className="score-breakdown">
              Raw: {s.rawScore} pts
              {s.strategyScore !== 0 && (
                <span className={`strategy-delta ${s.strategyScore > 0 ? 'positive' : 'negative'}`}>
                  {s.strategyScore > 0 ? ` +${s.strategyScore}` : ` ${s.strategyScore}`} strategic
                </span>
              )}
            </span>
          </div>
          {s.strategyReasons && s.strategyReasons.length > 0 && (
            <ul className="strategy-reasons">
              {s.strategyReasons.map((reason, i) => (
                <li key={i}>{reason}</li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  )
}
