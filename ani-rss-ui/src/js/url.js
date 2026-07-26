const MAX_EXTERNAL_URL_LENGTH = 8192

export function safeHttpUrl(value) {
  if (typeof value !== 'string') return ''

  const candidate = value.trim()
  if (!candidate || candidate.length > MAX_EXTERNAL_URL_LENGTH) return ''

  try {
    const url = new URL(candidate)
    if (!['http:', 'https:'].includes(url.protocol)) return ''
    if (url.username || url.password) return ''
    return url.toString()
  } catch {
    return ''
  }
}

export function openHttpUrl(value) {
  const url = safeHttpUrl(value)
  if (!url) return false

  window.open(url, '_blank', 'noopener,noreferrer')
  return true
}
