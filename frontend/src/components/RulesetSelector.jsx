import React from 'react'

export default function RulesetSelector({ value, onChange }) {
  return (
    <div className="ruleset-selector">
      <label>Game Edition</label>
      <div className="ruleset-options">
        {['US', 'UK'].map((r) => (
          <button
            key={r}
            className={`ruleset-btn ${value === r ? 'active' : ''}`}
            onClick={() => onChange(r)}
            type="button"
          >
            {r === 'US' ? 'US (TWL06)' : 'UK (Collins)'}
          </button>
        ))}
      </div>
    </div>
  )
}
