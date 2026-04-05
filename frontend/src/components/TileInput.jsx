import React from 'react'

export default function TileInput({ value, onChange }) {
  function handleChange(e) {
    const raw = e.target.value.toUpperCase().replace(/[^A-Z_]/g, '')
    if (raw.length <= 7) onChange(raw)
  }

  return (
    <div className="tile-input">
      <label htmlFor="tiles">Your Tiles</label>
      <input
        id="tiles"
        type="text"
        value={value}
        onChange={handleChange}
        maxLength={7}
        placeholder="e.g. AEINRST"
        autoComplete="off"
        spellCheck="false"
      />
      <span className="tile-hint">
        Up to 7 letters. Use _ for a blank tile. ({value.length}/7)
      </span>
      <div className="tile-display">
        {value.split('').map((letter, i) => (
          <span key={i} className="tile">
            {letter === '_' ? <em>blank</em> : letter}
          </span>
        ))}
      </div>
    </div>
  )
}
