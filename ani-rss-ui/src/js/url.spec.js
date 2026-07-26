import {afterEach, describe, expect, it, vi} from 'vitest'
import {openHttpUrl, safeHttpUrl} from './url.js'

describe('safe external URLs', () => {
  afterEach(() => vi.restoreAllMocks())

  it('accepts only absolute HTTP and HTTPS URLs without credentials', () => {
    expect(safeHttpUrl('https://example.com/path?q=1')).toBe('https://example.com/path?q=1')
    expect(safeHttpUrl('http://example.com')).toBe('http://example.com/')
    expect(safeHttpUrl('/relative')).toBe('')
    expect(safeHttpUrl('javascript:alert(1)')).toBe('')
    expect(safeHttpUrl('data:text/html,unsafe')).toBe('')
    expect(safeHttpUrl('https://user:secret@example.com')).toBe('')
  })

  it('opens a validated URL without opener access', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)

    expect(openHttpUrl('https://example.com/release')).toBe(true)
    expect(open).toHaveBeenCalledWith(
      'https://example.com/release', '_blank', 'noopener,noreferrer'
    )
  })

  it('does not open a rejected URL', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)

    expect(openHttpUrl('javascript:alert(1)')).toBe(false)
    expect(open).not.toHaveBeenCalled()
  })
})
