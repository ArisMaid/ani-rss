// @vitest-environment jsdom

import {afterEach, describe, expect, it, vi} from 'vitest'
import {nextTick} from 'vue'
import {mount} from '@vue/test-utils'

const http = vi.hoisted(() => ({
  getSubtitles: vi.fn()
}))
const globalState = vi.hoisted(() => ({
  toApiMedia: vi.fn(value => `/api/media/${value}`)
}))
const messages = vi.hoisted(() => ({
  error: vi.fn(),
  warning: vi.fn(),
  success: vi.fn()
}))

vi.mock('@/js/http.js', () => http)
vi.mock('@/js/global.js', () => globalState)
vi.mock('element-plus', () => ({ElMessage: messages}))
vi.mock('./ArtplayerView.vue', () => ({
  default: {
    name: 'ArtplayerViewStub',
    props: ['playItem'],
    template: `<div class="player-stub" :data-src="playItem.src">
      <span v-for="subtitle in playItem.subtitles" :key="subtitle.name">{{ subtitle.url }}</span>
    </div>`
  }
}))

import PlayStartView from './PlayStartView.vue'

const dialog = {
  props: ['modelValue', 'title'],
  emits: ['close'],
  template: `<div v-if="modelValue" class="dialog-stub">
    <slot/>
    <slot name="footer"/>
    <button class="dialog-close" @click="$emit('close')">关闭</button>
  </div>`
}

const text = {
  template: '<span class="text-stub"><slot/></span>'
}

const tick = async () => {
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

describe('play media contracts', () => {
  let wrapper
  let createObjectUrl
  let revokeObjectUrl

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.clearAllMocks()
    delete URL.createObjectURL
    delete URL.revokeObjectURL
    createObjectUrl = undefined
    revokeObjectUrl = undefined
  })

  const mountView = () => mount(PlayStartView, {
    global: {
      stubs: {
        'el-dialog': dialog,
        'el-text': text
      }
    }
  })

  it('shows the video before the slow internal subtitle request resolves', async () => {
    let resolveSubtitles
    http.getSubtitles.mockImplementation(() => new Promise(resolve => {
      resolveSubtitles = resolve
    }))
    createObjectUrl = vi.fn(() => 'blob:internal-subtitle')
    revokeObjectUrl = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', {configurable: true, value: createObjectUrl})
    Object.defineProperty(URL, 'revokeObjectURL', {configurable: true, value: revokeObjectUrl})

    wrapper = mountView()
    wrapper.vm.show({
      name: 'episode.mkv',
      filename: 'episode.mkv',
      extName: 'mkv',
      subtitles: [{name: 'external', url: 'subtitles/episode.ass'}]
    })
    await tick()

    expect(wrapper.find('.player-stub').attributes('data-src')).toBe('/api/media/episode.mkv')
    expect(http.getSubtitles).toHaveBeenCalledWith('episode.mkv', expect.objectContaining({
      signal: expect.any(AbortSignal)
    }))
    expect(wrapper.text()).toContain('正在读取内封字幕')
    expect(wrapper.findAll('.player-stub span')).toHaveLength(1)
    expect(wrapper.find('.player-stub span').text()).toBe('/api/media/subtitles/episode.ass')

    resolveSubtitles({data: [{name: 'internal', content: 'WEBVTT\n\n00:00.000 --> 00:01.000\n字幕'}]})
    await tick()

    expect(createObjectUrl).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.player-stub span')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('正在读取内封字幕')

    await wrapper.find('.dialog-close').trigger('click')
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:internal-subtitle')
    expect(wrapper.find('.player-stub').exists()).toBe(false)
  })

  it('ignores a subtitle response that arrives after the dialog was closed', async () => {
    let resolveSubtitles
    http.getSubtitles.mockImplementation(() => new Promise(resolve => {
      resolveSubtitles = resolve
    }))
    createObjectUrl = vi.fn(() => 'blob:late-subtitle')
    Object.defineProperty(URL, 'createObjectURL', {configurable: true, value: createObjectUrl})
    Object.defineProperty(URL, 'revokeObjectURL', {configurable: true, value: vi.fn()})

    wrapper = mountView()
    wrapper.vm.show({name: 'late.mkv', filename: 'late.mkv', subtitles: []})
    await tick()
    await wrapper.find('.dialog-close').trigger('click')

    resolveSubtitles({data: [{name: 'late', content: 'WEBVTT'}]})
    await tick()

    expect(createObjectUrl).not.toHaveBeenCalled()
    expect(wrapper.find('.player-stub').exists()).toBe(false)
  })

  it('keeps playback available when the optional subtitle endpoint fails', async () => {
    http.getSubtitles.mockRejectedValue(new Error('subtitle source unavailable'))
    wrapper = mountView()
    wrapper.vm.show({name: 'playable.mp4', filename: 'playable.mp4', subtitles: []})
    await tick()

    expect(wrapper.find('.player-stub').exists()).toBe(true)
    expect(wrapper.text()).toContain('内封字幕加载失败，可继续播放视频')
  })
})
