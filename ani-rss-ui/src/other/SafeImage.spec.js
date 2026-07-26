import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import SafeImage from './SafeImage.vue'
import * as http from '@/js/http.js'

vi.mock('@/js/http.js', () => ({
  cacheImage: vi.fn()
}))

describe('SafeImage', () => {
  beforeEach(() => {
    vi.mocked(http.cacheImage).mockResolvedValue({data: {id: 'cached-image-id'}})
  })

  it('never places the upstream URL in the image source', async () => {
    const upstream = 'https://images.example.test/avatar.png?token=secret'
    const wrapper = mount(SafeImage, {
      props: {srcUrl: upstream},
      attrs: {alt: 'avatar'}
    })

    expect(wrapper.find('img').exists()).toBe(false)
    await flushPromises()

    const image = wrapper.get('img')
    expect(http.cacheImage).toHaveBeenCalledWith(upstream)
    expect(image.attributes('src')).toContain('/api/v2/images/cached-image-id')
    expect(image.attributes('src')).not.toContain('images.example.test')
    expect(image.attributes('alt')).toBe('avatar')
  })

  it('renders no image when caching fails', async () => {
    vi.mocked(http.cacheImage).mockRejectedValue(new Error('blocked'))
    const wrapper = mount(SafeImage, {props: {srcUrl: 'https://example.test/image.png'}})

    await flushPromises()

    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('defers a lazy cache request until its cover enters the viewport', async () => {
    let intersect = () => {}
    const observe = vi.fn()
    const disconnect = vi.fn()
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback) {
        intersect = () => callback([{isIntersecting: true}])
      }

      observe(...args) {
        observe(...args)
      }

      disconnect(...args) {
        disconnect(...args)
      }
    })
    try {
      const wrapper = mount(SafeImage, {
        props: {srcUrl: 'https://example.test/cover.png', lazy: true}
      })

      await flushPromises()
      expect(observe).toHaveBeenCalledTimes(1)
      expect(http.cacheImage).not.toHaveBeenCalled()

      intersect()
      await flushPromises()

      expect(http.cacheImage).toHaveBeenCalledWith('https://example.test/cover.png')
      expect(disconnect).toHaveBeenCalledTimes(1)
      expect(wrapper.get('img').attributes('src')).toContain('/api/v2/images/cached-image-id')
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
