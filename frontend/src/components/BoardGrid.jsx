import React, { useEffect, useRef } from 'react'

const SIZE = 15
const COL_LABELS = 'ABCDEFGHIJKLMNO'.split('')

const SQ_CSS = {
  TRIPLE_WORD: 'sq-TW',
  DOUBLE_WORD: 'sq-DW',
  TRIPLE_LETTER: 'sq-TL',
  DOUBLE_LETTER: 'sq-DL',
  STANDARD: 'sq-std',
}

/**
 * Interactive 15×15 Scrabble board.
 *
 * Props:
 *   cells        — 2D array [row][col] of letter strings or null
 *   squareTypes  — 2D array [row][col] of SquareType strings (STANDARD, DOUBLE_LETTER, etc.)
 *                  If not provided, all squares render as STANDARD
 *   selectedCell — {row, col} or null
 *   onCellClick  — (row, col) => void
 *   onKeyDown    — (e) => void
 *   readOnly     — bool
 */
export default function BoardGrid({ cells, squareTypes, selectedCell, onCellClick, onKeyDown, readOnly = false }) {
  const trapRef = useRef(null)

  useEffect(() => {
    if (!readOnly && selectedCell && trapRef.current) {
      trapRef.current.focus()
    }
  }, [selectedCell, readOnly])

  function handleKeyDown(e) {
    if (readOnly) return
    if (onKeyDown) onKeyDown(e)
  }

  return (
    <div className="board-wrapper">
      <div className="board-col-labels">
        <div className="board-corner" />
        {COL_LABELS.map(l => <div key={l} className="board-axis-label">{l}</div>)}
      </div>

      <div className="board-rows">
        {Array.from({ length: SIZE }, (_, row) => (
          <div key={row} className="board-row">
            <div className="board-axis-label board-row-label">{row + 1}</div>

            {Array.from({ length: SIZE }, (_, col) => {
              const letter = cells?.[row]?.[col] ?? null
              const sqType = squareTypes?.[row]?.[col] ?? 'STANDARD'
              const sqClass = SQ_CSS[sqType] ?? 'sq-std'
              const isSelected = selectedCell?.row === row && selectedCell?.col === col
              const isCenter = row === 7 && col === 7

              let cellClass = `board-cell ${sqClass}`
              if (letter) cellClass += ' occupied'
              if (isSelected) cellClass += ' selected'
              if (!readOnly) cellClass += ' interactive'

              const sqLabel = sqType !== 'STANDARD'
                ? sqType.replace('DOUBLE_LETTER', '2L').replace('TRIPLE_LETTER', '3L').replace('DOUBLE_WORD', '2W').replace('TRIPLE_WORD', '3W')
                : null

              return (
                <div
                  key={col}
                  className={cellClass}
                  onClick={() => !readOnly && onCellClick(row, col)}
                  title={sqType !== 'STANDARD' ? sqType.replace(/_/g, ' ') : undefined}
                >
                  {letter ? (
                    <span className="cell-letter">{letter}</span>
                  ) : isCenter ? (
                    <span className="cell-star">★</span>
                  ) : sqLabel ? (
                    <span className="cell-sq-label">{sqLabel}</span>
                  ) : null}
                </div>
              )
            })}
          </div>
        ))}
      </div>

      {!readOnly && (
        <div
          ref={trapRef}
          tabIndex={0}
          className="board-keyboard-trap"
          onKeyDown={handleKeyDown}
        />
      )}
    </div>
  )
}
