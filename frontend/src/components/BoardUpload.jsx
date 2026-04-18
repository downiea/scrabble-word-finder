import React, { useRef, useState } from 'react'

export default function BoardUpload({ onImageSelected, onImageTypeChange }) {
  const libraryInputRef = useRef(null)
  const cameraInputRef = useRef(null)
  const [fileName, setFileName] = useState(null)
  const [imageType, setImageType] = useState('DIGITAL')

  function handleFile(file) {
    if (!file || !file.type.startsWith('image/')) return
    setFileName(file.name)
    const url = URL.createObjectURL(file)
    onImageSelected(file, url)
  }

  function handleChange(e) {
    handleFile(e.target.files[0])
  }

  function handleDrop(e) {
    e.preventDefault()
    handleFile(e.dataTransfer.files[0])
  }

  function handleTypeToggle(type) {
    setImageType(type)
    onImageTypeChange(type)
  }

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

      <div
        className="drop-zone compact"
        onDragOver={(e) => e.preventDefault()}
        onDrop={handleDrop}
      >
        {fileName ? (
          <div className="file-loaded">
            <span className="file-name">{fileName}</span>
            <button type="button" className="upload-btn sm" onClick={() => libraryInputRef.current.click()}>
              Change
            </button>
          </div>
        ) : (
          <>
            <div className="upload-actions">
              <button type="button" className="upload-btn" onClick={() => cameraInputRef.current.click()}>
                Take Photo
              </button>
              <button type="button" className="upload-btn" onClick={() => libraryInputRef.current.click()}>
                Choose Image
              </button>
            </div>
            <p className="drop-hint">or drag and drop</p>
          </>
        )}
      </div>

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
