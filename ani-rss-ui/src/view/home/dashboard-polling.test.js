// @vitest-environment jsdom

import {afterEach, describe, expect, it, vi} from 'vitest'
import {nextTick} from 'vue'
import {mount} from '@vue/test-utils'

const http = vi.hoisted(() => ({
  listAni: vi.fn(() => Promise.resolve({data: {weekList: [], total: 0}})),
  config: vi.fn(() => Promise.resolve({data: {procrastinatingDay: 14}})),
  torrentsInfos: vi.fn()
}))

const messages = vi.hoisted(() => ({
  error: vi.fn(),
  warning: vi.fn(),
  success: vi.fn()
}))

vi.mock('@/js/http.js', () => http)
vi.mock('element-plus', () => ({ElMessage: messages}))
vi.mock('@/view/custom/SafeImageView.vue', () => ({
  default: {template: '<span class="safe-image-stub" />'}
}))

import DashboardView from './DashboardView.vue'

const passthrough = {
  inheritAttrs: false,
  template: '<div><slot name="default"/><slot name="actions"/><slot/></div>'
}

const noRender = {template: '<div/>'}

const pageHeader = {
  props: ['title', 'subtitle'],
  template: '<header><span>{{ title }}</span><span>{{ subtitle }}</span><slot name="actions"/></header>'
}

const button = {
  props: ['loading', 'disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot/></button>'
}

const stubs = {
  PageHeaderView: pageHeader,
  'el-button': button,
  'el-empty': passthrough,
  'el-icon': passthrough,
  'el-progress': passthrough,
  'el-scrollbar': passthrough,
  'el-table': passthrough,
  'el-table-column': noRender,
  'el-tag': passthrough,
  AniCoverView: passthrough,
  'el-dialog': passthrough,
  'el-text': passthrough
}

const tick = async () => {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

const response = (name = 'slow task') => ({
  data: [{
    name,
    state: 'downloading',
    progress: 42,
    formatSize: '1 KB'
  }]
})

describe('dashboard polling contract', () => {
  let wrapper
  let pending

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    pending = undefined
    vi.useRealTimers()
    vi.clearAllMocks()
    Object.defineProperty(document, 'hidden', {configurable: true, value: false})
  })

  const mountView = () => mount(DashboardView, {
    global: {
      stubs,
      directives: {loading: {}}
    }
  })

  const deferredRequest = () => {
    let resolve
    let reject
    const promise = new Promise((res, rej) => {
      resolve = res
      reject = rej
    })
    pending = {resolve, reject}
    return promise
  }

  it('keeps one slow downloader request in flight and shares it with manual refresh', async () => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'hidden', {configurable: true, value: false})
    http.torrentsInfos.mockImplementation(() => deferredRequest())

    wrapper = mountView()
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(1)
    wrapper.vm.startPolling()
    await tick()

    const manualRefresh = wrapper.vm.loadAll()
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(1)

    pending.resolve(response())
    await manualRefresh
    await tick()

    await vi.advanceTimersByTimeAsync(5_000)
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(2)

    const secondRefresh = wrapper.vm.loadAll()
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(2)
    pending.resolve(response('second slow task'))
    await secondRefresh
    await tick()
  })

  it('does not report cancellation or schedule while hidden, then resumes when visible', async () => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'hidden', {configurable: true, value: false})
    http.torrentsInfos.mockImplementation((_options = {}) => {
      const request = deferredRequest()
      const signal = _options.signal
      signal?.addEventListener('abort', () => {
        const error = new Error('cancelled')
        error.code = 'REQUEST_ABORTED'
        request.reject(error)
      }, {once: true})
      return request
    })

    wrapper = mountView()
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(1)
    wrapper.vm.startPolling()
    await tick()
    pending.resolve(response('visible task'))
    await tick()
    await vi.advanceTimersByTimeAsync(5_000)
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(2)

    Object.defineProperty(document, 'hidden', {configurable: true, value: true})
    document.dispatchEvent(new Event('visibilitychange'))
    await tick()
    expect(http.torrentsInfos.mock.calls[1][0].signal.aborted).toBe(true)
    vi.advanceTimersByTime(20_000)
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(2)
    expect(messages.error).not.toHaveBeenCalled()

    Object.defineProperty(document, 'hidden', {configurable: true, value: false})
    document.dispatchEvent(new Event('visibilitychange'))
    await tick()
    expect(http.torrentsInfos).toHaveBeenCalledTimes(3)
    pending.resolve(response('resumed task'))
    await tick()
  })
})
