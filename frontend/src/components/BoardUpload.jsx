import React, { useRef, useState } from 'react'

export default function BoardUpload({ onImageSelected, onImageTypeChange }) {
  const libraryInputRef = useRef(null)
  const cameraInputRef = useRef(null)
  const [preview, setPreview] = useState(null)
  const [dragOver, setDragOver] = useState(false)
  const [imageType, setImageType] = useState('DIGITAL')

  function handleFile(file) {
    if (!file || !file.type.startsWith('image/')) return
    setPreview(URL.createObjectURL(file))
    onImageSelected(file)
  }

  function handleChange(e) {
    handleFile(e.target.files[0])
  }

  function handleDrop(e) {
    e.preventDefault()
    setDragOver(false)
    handleFile(e.dataTransfer.files[0])
  }

  function handleTypeToggle(type) {
    setImageType(type)
    onImageTypeChange(type)
  }

  const hint = imageType === 'DIGITAL'
    ? 'Screenshot from a Scrabble app (e.g. Scrabble GO, Words With Friends)'
    : 'Photo of a physical Scrabble board'

  return (
    <div className="board-upload">
      <label>Board Image</label>

      <div className="image-type-toggle">
        <button
          type="button"
          className={`toggle-btn ${imageType === 'DIGITAL' ? 'active' : ''}`}
          onClick={() => handleTypeToggle('DIGITAL')}
        >
          Digital
        </button>
        <button
          type="button"
          className={`toggle-btn ${imageType === 'PHYSICAL' ? 'active' : ''}`}
          onClick={() => handleTypeToggle('PHYSICAL')}
        >
          Physical board
        </button>
      </div>

      {preview ? (
        <div
          className="drop-zone"
          onClick={() => libraryInputRef.current.click()}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
        >
          <img src={preview} alt="Board preview" className="board-preview" />
        </div>
      ) : (
        <div
          className={`drop-zone ${dragOver ? 'drag-over' : ''}`}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
        >
          <p className="drop-hint">{hint}</p>
          <div className="upload-actions">
            <button type="button" className="upload-btn" onClick={() => cameraInputRef.current.click()}>
              Take Photo
            </button>
            <button type="button" className="upload-btn" onClick={() => libraryInputRef.current.click()}>
              Choose Image
            </button>
          </div>
        </div>
      )}

      <input
        ref={libraryInputRef}
        type="file"
        accept="image/*"
        onChange={handleChange}
        style={{ display: 'none' }}
      />
      <input
        ref={cameraInputRef}
        type="file"
        accept="image/*"
        capture="environment"
        onChange={handleChange}
        style={{ display: 'none' }}
      />
    </div>
  )
}
