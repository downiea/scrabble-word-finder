const API_BASE = '/api'

export async function fetchGameConfigs() {
  const response = await fetch(`${API_BASE}/game-configs`)
  if (!response.ok) throw new Error('Failed to load game configs')
  return response.json()
}

/**
 * Step 1 — extract board state, square types, and rack tiles from an image.
 */
export async function extractBoard(imageFile, imageType = 'DIGITAL', gameConfigId = 'unknown') {
  const formData = new FormData()
  formData.append('image', imageFile)
  formData.append('imageType', imageType)
  formData.append('gameConfigId', gameConfigId)

  const response = await fetch(`${API_BASE}/extract`, {
    method: 'POST',
    body: formData,
  })

  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(`Extraction failed (${response.status}): ${errorText}`)
  }

  return response.json()
}

/**
 * Step 2 — generate move suggestions from a confirmed board state.
 *
 * @param {Array<{row, col, letter, squareType}>} cells
 * @param {string} tiles
 * @param {string} ruleset  "US" | "UK"
 * @param {string} gameConfigId
 */
export async function analyseBoard(cells, tiles, ruleset, gameConfigId = 'unknown') {
  const response = await fetch(`${API_BASE}/analyse/board`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cells, tiles: tiles.toUpperCase(), ruleset, gameConfigId }),
  })

  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(`Analysis failed (${response.status}): ${errorText}`)
  }

  return response.json()
}
