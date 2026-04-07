import React, { useRef, useState, useEffect, useCallback } from 'react'
import { saveGameConfigCrops } from '../services/api'

function clamp(v, min, max) {
  return Math.max(min, Math.min(max, v))
}

const DEFAULT_BOARD_CROP = { left: 0.02, top: 0.05, width: 0.72, height: 0.80 }
const DEFAULT_TILES_CROP = { left: 0.02, top: 0.88, width: 0.72, height: 0.10 }

const REGION_STYLES = {
  board: { border: '#1a6b2a', bg: 'rgba(26,107,42,0.18)', label: 'Board' },
  tiles: { border: '#1a4f96', bg: 'rgba(26,79,150,0.18)', label: 'Tiles' },
}

const HANDLE_CURSORS = {
  nw: 'nw-resize', n: 'n-resize', ne: 'ne-resize',
  e: 'e-resize', se: 'se-resize', s: 's-resize',
  sw: 'sw-resize', w: 'w-resize',
}

function CropOverlay({ crop, which, color, border, label, onStartDrag }) {
  const { left, top, width, height } = crop
  return (
    <div
      style={{
        position: 'absolute',
        left: `${left * 100}%`,
        top: `${top * 100}%`,
        width: `${width * 100}%`,
        height: `${height * 100}%`,
        border: `2px solid ${border}`,
        background: color,
        boxSizing: 'border-box',
        cursor: 'move',
        userSelect: 'none',
      }}
      onMouseDown={(e) => onStartDrag(e, which, 'move')}
    >
      <span style={{
        position: 'absolute', top: 2, left: 4,
        fontSize: 10, fontWeight: 700, color: border,
        background: 'rgba(255,255,255,0.75)', padding: '0 3px', borderRadius: 2,
        pointerEvents: 'none',
      }}>
        {label}
      </span>

      {Object.keys(HANDLE_CURSORS).map(h => {
        const s = {
          position: 'absolute',
          width: 10, height: 10,
          background: border,
          borderRadius: '50%',
          cursor: HANDLE_CURSORS[h],
        }
        if (h.includes('n')) s.top = -5; else if (h.includes('s')) s.bottom = -5; else { s.top = '50%'; s.marginTop = -5 }
        if (h.includes('w')) s.left = -5; else if (h.includes('e')) s.right = -5; else { s.left = '50%'; s.marginLeft = -5 }
        return (
          <div
            key={h}
            style={s}
            onMouseDown={(e) => { e.stopPropagation(); onStartDrag(e, which, h) }}
          />
        )
      })}
    </div>
  )
}

export default function CropEditor({ imageUrl, gameConfigId, initialBoardCrop, initialTilesCrop, onSaved }) {
  const containerRef = useRef(null)
  const dragRef = useRef(null)

  const [boardEnabled, setBoardEnabled] = useState(!!initialBoardCrop)
  const [tilesEnabled, setTilesEnabled] = useState(!!initialTilesCrop)
  const [boardCrop, setBoardCrop] = useState(initialBoardCrop ?? DEFAULT_BOARD_CROP)
  const [tilesCrop, setTilesCrop] = useState(initialTilesCrop ?? DEFAULT_TILES_CROP)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  function startDrag(e, which, handle) {
    e.preventDefault()
    const rect = containerRef.current?.getBoundingClientRect()
    if (!rect) return
    const crop = which === 'board' ? boardCrop : tilesCrop
    dragRef.current = { which, handle, startX: e.clientX, startY: e.clientY, containerW: rect.width, containerH: rect.height, startCrop: { ...crop } }
  }

  const handleMouseMove = useCallback((e) => {
    const d = dragRef.current
    if (!d) return
    const dx = (e.clientX - d.startX) / d.containerW
    const dy = (e.clientY - d.startY) / d.containerH
    const { left: l0, top: t0, width: w0, height: h0 } = d.startCrop
    const MIN_W = 0.05, MIN_H = 0.02

    let left = l0, top = t0, width = w0, height = h0

    if (d.handle === 'move') {
      left = clamp(l0 + dx, 0, 1 - w0)
      top = clamp(t0 + dy, 0, 1 - h0)
    } else {
      if (d.handle.includes('e')) width = clamp(w0 + dx, MIN_W, 1 - l0)
      if (d.handle.includes('s')) height = clamp(h0 + dy, MIN_H, 1 - t0)
      if (d.handle.includes('w')) {
        const newLeft = clamp(l0 + dx, 0, l0 + w0 - MIN_W)
        width = w0 + l0 - newLeft
        left = newLeft
      }
      if (d.handle.includes('n')) {
        const newTop = clamp(t0 + dy, 0, t0 + h0 - MIN_H)
        height = h0 + t0 - newTop
        top = newTop
      }
    }

    const next = { left, top, width, height }
    if (d.which === 'board') setBoardCrop(next)
    else setTilesCrop(next)
  }, [])

  const handleMouseUp = useCallback(() => { dragRef.current = null }, [])

  useEffect(() => {
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }
  }, [handleMouseMove, handleMouseUp])

  async function handleSave() {
    setSaving(true)
    setSaved(false)
    try {
      const bc = boardEnabled ? boardCrop : null
      const tc = tilesEnabled ? tilesCrop : null
      await saveGameConfigCrops(gameConfigId, bc, tc)
      setSaved(true)
      if (onSaved) onSaved(bc, tc)
    } catch (err) {
      alert('Failed to save crops: ' + err.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="crop-editor">
      <div className="crop-editor-controls">
        <label className="crop-toggle">
          <input type="checkbox" checked={boardEnabled} onChange={e => { setBoardEnabled(e.target.checked); setSaved(false) }} />
          <span style={{ color: REGION_STYLES.board.border }}>Board region</span>
        </label>
        <label className="crop-toggle">
          <input type="checkbox" checked={tilesEnabled} onChange={e => { setTilesEnabled(e.target.checked); setSaved(false) }} />
          <span style={{ color: REGION_STYLES.tiles.border }}>Tiles region</span>
        </label>
      </div>

      {(boardEnabled || tilesEnabled) && (
        <p className="crop-hint">Drag the rectangle to move it. Drag a corner or edge handle to resize.</p>
      )}

      <div ref={containerRef} className="crop-image-container">
        <img src={imageUrl} alt="Crop preview" className="crop-preview-img" draggable={false} />
        {boardEnabled && (
          <CropOverlay
            crop={boardCrop} which="board"
            color={REGION_STYLES.board.bg} border={REGION_STYLES.board.border} label="Board"
            onStartDrag={startDrag}
          />
        )}
        {tilesEnabled && (
          <CropOverlay
            crop={tilesCrop} which="tiles"
            color={REGION_STYLES.tiles.bg} border={REGION_STYLES.tiles.border} label="Tiles"
            onStartDrag={startDrag}
          />
        )}
      </div>

      <button className="crop-save-btn" onClick={handleSave} disabled={saving}>
        {saving ? 'Saving…' : saved ? 'Saved ✓' : 'Save crop regions to config'}
      </button>
    </div>
  )
}
