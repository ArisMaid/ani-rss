import {beforeEach, describe, expect, it, vi} from 'vitest'
import api from './api.js'
import {cacheImage} from './http.js'

vi.mock('./api.js', () => ({default: {post: vi.fn()}}))

describe('cacheImage requests', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockReset()
  })

  it('deduplicates concurrent image cache requests and stays silent', async () => {
    const response = {data: {id: 'image-id'}}
    vi.mocked(api.post).mockResolvedValue(response)
    const url = 'https://images.example.test/cover.png'

    const first = cacheImage(url)
    const second = cacheImage(url)

    expect(first).toBe(second)
    expect(api.post).toHaveBeenCalledOnce()
    expect(api.post).toHaveBeenCalledWith('api/v2/images', {url}, {silent: true})
    await first

    await cacheImage(url)
    expect(api.post).toHaveBeenCalledTimes(2)
  })

  it('limits unique upstream image requests to six at a time', async () => {
    const response = {data: {id: 'image-id'}}
    const releases = []
    vi.mocked(api.post).mockImplementation(() =>
      new Promise(resolve => releases.push(resolve)))

    const requests = Array.from({length: 8}, (_, index) =>
      cacheImage(`https://images.example.test/${index}.png`))

    expect(api.post).toHaveBeenCalledTimes(6)
    releases.shift()(response)
    await vi.waitFor(() => expect(api.post).toHaveBeenCalledTimes(7))
    releases.shift()(response)
    await vi.waitFor(() => expect(api.post).toHaveBeenCalledTimes(8))
    for (const release of releases.splice(0)) release(response)

    await Promise.all(requests)
  })
})
