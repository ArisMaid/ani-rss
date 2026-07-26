import {describe, expect, it} from 'vitest'
import {renderSafeMarkdown} from './markdown.js'

describe('renderSafeMarkdown', () => {
    it('does not create elements from raw HTML or javascript links', () => {
        const container = document.createElement('div')
        container.innerHTML = renderSafeMarkdown(
            '<img src=x onerror=alert(1)> [unsafe](javascript:alert(1))'
        )

        expect(container.querySelector('img')).toBeNull()
        expect([...container.querySelectorAll('a')]
            .some(link => link.href.startsWith('javascript:'))).toBe(false)
    })

    it('opens generated external links without opener access', () => {
        const container = document.createElement('div')
        container.innerHTML = renderSafeMarkdown('[release](https://example.com/release)')
        const link = container.querySelector('a')

        expect(link?.getAttribute('target')).toBe('_blank')
        expect(link?.getAttribute('rel')).toBe('noopener noreferrer')
    })
})
