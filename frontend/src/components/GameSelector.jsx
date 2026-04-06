import React from 'react'

export default function GameSelector({ configs, value, onChange }) {
  if (!configs?.length) return null

  return (
    <div className="game-selector">
      <label htmlFor="game-type">Game Type</label>
      <select
        id="game-type"
        value={value}
        onChange={e => onChange(e.target.value)}
      >
        {configs.map(c => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>
      {value && configs.find(c => c.id === value)?.description && (
        <span className="game-selector-hint">
          {configs.find(c => c.id === value).description}
        </span>
      )}
    </div>
  )
}
