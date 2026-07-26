import {beforeEach, describe, expect, it, vi} from 'vitest'
import api from './api.js'

const messages = vi.hoisted(() => ({error: vi.fn()}))

vi.mock('element-plus', () => ({ElMessage: messages}))
vi.mock('./global.js', () => ({
  clearAuthentication: vi.fn(),
  csrfToken: {value: ''}
}))

describe('api error presentation', () => {
  beforeEach(() => {
    messages.error.mockReset()
  })

  it('can keep expected background failures silent', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => ({detail: 'image fetch failed'})
    }))

    await expect(api.post('api/v2/images', {url: 'https://example.test/a.png'}, {silent: true}))
      .rejects.toMatchObject({status: 502})
    expect(messages.error).not.toHaveBeenCalled()
  })

  it('keeps foreground failures visible', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({detail: 'invalid request'})
    }))

    await expect(api.post('api/v2/config', {}, {})).rejects.toThrow('invalid request')
    expect(messages.error).toHaveBeenCalledWith('invalid request')
  })

  it('reports a network failure once for foreground requests', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))

    await expect(api.post('api/rssToAni', {})).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      status: 0,
      message: '网络请求失败'
    })
    expect(messages.error).toHaveBeenCalledOnce()
    expect(messages.error).toHaveBeenCalledWith('网络请求失败')
  })

  it('keeps background network failures silent', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))

    await expect(api.post('api/v2/images', {url: 'https://example.test/a.png'}, {silent: true}))
      .rejects.toMatchObject({code: 'NETWORK_ERROR', status: 0})
    expect(messages.error).not.toHaveBeenCalled()
  })
})
