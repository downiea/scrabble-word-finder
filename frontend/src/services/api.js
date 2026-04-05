const API_BASE = '/api'

/**
 * Analyse a Scrabble board image.
 *
 * @param {File}   imageFile  - the board image
 * @param {string} tiles      - player's rack, e.g. "AEINRST" (use _ for blank)
 * @param {string} ruleset    - "US" or "UK"
 * @returns {Promise<AnalysisResponse>}
 */
export async function analyseBoard(imageFile, tiles, ruleset) {
  const formData = new FormData()
  formData.append('image', imageFile)
  formData.append('tiles', tiles.toUpperCase())
  formData.append('ruleset', ruleset)

  const response = await fetch(`${API_BASE}/analyse`, {
    method: 'POST',
    body: formData,
  })

  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(`Analysis failed (${response.status}): ${errorText}`)
  }

  return response.json()
}
