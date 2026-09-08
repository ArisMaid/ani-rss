// @vitest-environment jsdom

import {afterEach, describe, expect, it, vi} from 'vitest'
import {nextTick} from 'vue'
import {mount} from '@vue/test-utils'

const http = vi.hoisted(() => ({
  mikan: vi.fn(),
  mikanScores: vi.fn(() => Promise.resolve({data: {scores: {}, retryableMikanIds: []}})),
  mikanGroup: vi.fn(),
  animeGardenList: vi.fn(),
  animeGardenEnrichment: vi.fn(() => Promise.resolve({data: {
    subjects: {}, retryableSubjectIds: []
  }})),
  animeGardenGroup: vi.fn()
}))
const globalState = vi.hoisted(() => ({showScore: {__v_isRef: true, value: true}}))

vi.mock('@/js/http.js', () => http)
vi.mock('@/js/global.js', () => globalState)
vi.mock('element-plus', () => ({
  ElMessage: {error: vi.fn(), warning: vi.fn(), success: vi.fn()},
  ElText: {name: 'ElText'}
}))
vi.mock('@/view/custom/SafeImageView.vue', () => ({
  default: {template: '<span class="safe-image-stub" />'}
}))

import MikanView from './MikanView.vue'
import AnimeGardenView from './AnimeGardenView.vue'

const passthrough = {
  inheritAttrs: false,
  template: '<div><slot name="title"/><slot/></div>'
}
const dialog = {
  inheritAttrs: false,
  props: ['modelValue'],
  template: '<div v-if="modelValue"><slot/></div>'
}
const stubs = {
  'el-dialog': dialog,
  'el-progress': passthrough,
  'el-radio-group': passthrough,
  'el-radio': passthrough,
  'el-tag': passthrough,
  'el-button': passthrough,
  'el-input': passthrough,
  'el-select': passthrough,
  'el-option': passthrough,
  'el-checkbox-group': passthrough,
  'el-tabs': passthrough,
  'el-tab-pane': passthrough,
  'el-scrollbar': passthrough,
  'el-collapse': passthrough,
  'el-collapse-item': passthrough,
  'el-badge': passthrough,
  'el-text': passthrough,
  'el-card': passthrough,
  'el-checkbox': passthrough
}

const validMikanList = title => ({data: {
  seasons: [],
  weeks: [{weekLabel: '星期一', items: [{
    url: 'https://mikan.example/Home/Bangumi/123', title, cover: '', score: 0, exists: false
  }]}],
  totalItems: 1
}})

const multiWeekMikanList = () => ({data: {
  seasons: [],
  weeks: [
    {weekLabel: '星期一', items: [{
      url: 'https://mikan.example/Home/Bangumi/123', title: '周一', cover: '', score: 0, exists: false
    }]},
    {weekLabel: '星期二', items: [{
      url: 'https://mikan.example/Home/Bangumi/456', title: '周二', cover: '', score: 0, exists: false
    }]}
  ],
  totalItems: 2
}})

const validAnimeGardenList = title => ({data: [{
  weekLabel: '星期一', subjects: [{id: '123', name: title, cover: '', score: null, exists: false}]
}]})

const setHidden = value => {
  Object.defineProperty(document, 'hidden', {configurable: true, value})
  document.dispatchEvent(new Event('visibilitychange'))
}

const tick = async () => {
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

describe('list view lifecycle', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.clearAllMocks()
    setHidden(false)
  })

  it('restarts a cancelled Mikan list on visibility and clears loading', async () => {
    let firstSignal
    let resolveFirst
    let resolveSecond
    http.mikan
      .mockImplementationOnce((_text, _body, options) => {
        firstSignal = options.signal
        return new Promise(resolve => { resolveFirst = resolve })
      })
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve }))

    wrapper = mount(MikanView, {
      global: {
        stubs,
        directives: {loading: {}}
      }
    })
    wrapper.vm.show({title: '目标番剧'})
    await tick()
    expect(http.mikan).toHaveBeenCalledTimes(1)

    setHidden(true)
    await tick()
    expect(firstSignal.aborted).toBe(true)
    expect(wrapper.find('.scroll-container').attributes('data-loading')).toBe('false')

    setHidden(false)
    await tick()
    expect(http.mikan).toHaveBeenCalledTimes(2)
    resolveSecond(validMikanList('恢复后的列表'))
    await tick()
    expect(wrapper.text()).toContain('恢复后的列表')
    expect(wrapper.find('.scroll-container').attributes('data-loading')).toBe('false')
    resolveFirst?.(validMikanList('过期响应'))
  })

  it('restarts an AnimeGarden list after hiding during its initial load', async () => {
    let firstSignal
    let resolveSecond
    http.animeGardenList
      .mockImplementationOnce((_url, options) => {
        firstSignal = options.signal
        return new Promise(() => {})
      })
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve }))

    wrapper = mount(AnimeGardenView, {
      global: {
        stubs,
        directives: {loading: {}}
      }
    })
    wrapper.vm.show()
    await tick()
    expect(http.animeGardenList).toHaveBeenCalledTimes(1)

    setHidden(true)
    await tick()
    expect(firstSignal.aborted).toBe(true)
    setHidden(false)
    await tick()
    expect(http.animeGardenList).toHaveBeenCalledTimes(2)

    resolveSecond(validAnimeGardenList('AnimeGarden 恢复'))
    await tick()
    expect(wrapper.text()).toContain('AnimeGarden 恢复')
    expect(wrapper.find('.scroll-container').attributes('data-loading')).toBe('false')
  })

  it('keeps a completed Mikan list and resumes only the active enrichment after hiding', async () => {
    let resolveScores
    http.mikan.mockImplementation(() => Promise.resolve(validMikanList('已完成列表')))
    http.mikanScores.mockImplementationOnce((_ids, options) => new Promise(resolve => {
      expect(options.signal.aborted).toBe(false)
      resolveScores = resolve
    }))
    wrapper = mount(MikanView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show({title: '目标番剧'})
    await tick()
    expect(http.mikan).toHaveBeenCalledTimes(1)
    expect(http.mikanScores).toHaveBeenCalledTimes(1)

    setHidden(true)
    await tick()
    setHidden(false)
    await tick()

    expect(http.mikan).toHaveBeenCalledTimes(1)
    expect(http.mikanScores).toHaveBeenCalledTimes(2)
    resolveScores?.({data: {scores: {}, retryableMikanIds: []}})
  })

  it('shows the Mikan enrichment state while a score request is still pending', async () => {
    let resolveScores
    http.mikan.mockResolvedValue(validMikanList('评分状态列表'))
    http.mikanScores.mockImplementationOnce(() => new Promise(resolve => {
      resolveScores = resolve
    }))
    wrapper = mount(MikanView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show({title: '评分状态'})
    await tick()

    expect(wrapper.find('[data-enrichment-status]').text()).toContain('评分加载中')
    wrapper.vm.scoreStates['星期一'].status = 'incomplete'
    wrapper.vm.scoreStates['星期一'].pendingIds = ['123']
    await tick()
    expect(wrapper.find('[data-enrichment-status]').text()).toContain('重试剩余资源')
    resolveScores?.({data: {scores: {}, retryableMikanIds: []}})
  })

  it('restarts Mikan enrichment when switching A to B and back to A', async () => {
    const scoreRequests = []
    http.mikan.mockResolvedValue(multiWeekMikanList())
    http.mikanScores.mockReset().mockImplementation((ids, options) => {
      let resolve
      const promise = new Promise(nextResolve => { resolve = nextResolve })
      scoreRequests.push({ids, signal: options.signal, resolve})
      return promise
    })

    wrapper = mount(MikanView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show({title: '分组切换'})
    await tick()
    expect(scoreRequests).toHaveLength(1)
    expect(scoreRequests[0].ids).toEqual(['123'])

    wrapper.vm.activeName = '星期二'
    await tick()
    expect(scoreRequests).toHaveLength(2)
    expect(scoreRequests[0].signal.aborted).toBe(true)

    wrapper.vm.activeName = '星期一'
    await tick()
    expect(scoreRequests).toHaveLength(3)
    expect(scoreRequests[2].ids).toEqual(['123'])
    expect(wrapper.vm.scoreStates['星期一'].status).toBe('loading')

    scoreRequests[2].resolve({data: {scores: {'123': 8}, retryableMikanIds: []}})
    await tick()
    await tick()
    expect(wrapper.vm.scoreStates['星期一'].status).toBe('complete')
    expect(wrapper.vm.scoreStates['星期一'].pendingIds).toEqual([])

    http.mikanScores.mockReset().mockResolvedValue({data: {scores: {}, retryableMikanIds: []}})
  })

  it('uses the submitted Mikan query snapshot when the cancelled list resumes', async () => {
    let resolveSecond
    http.mikan
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockImplementationOnce((text, body) => {
        expect(body).toEqual({})
        expect(text).toBe('目标番剧')
        return new Promise(resolve => { resolveSecond = resolve })
      })
    wrapper = mount(MikanView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show({title: '目标番剧'})
    await tick()
    setHidden(true)
    await tick()
    wrapper.vm.text = '用户正在编辑但尚未提交'
    setHidden(false)
    await tick()

    expect(http.mikan).toHaveBeenCalledTimes(2)
    resolveSecond(validMikanList('按已提交查询恢复'))
    await tick()
    expect(wrapper.text()).toContain('按已提交查询恢复')
  })

  it('does not let a slow Mikan search overwrite a newer search', async () => {
    let resolveFirst
    let resolveSecond
    http.mikan
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve }))
    wrapper = mount(MikanView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show({title: '第一个'})
    await tick()
    wrapper.vm.text = '第二个'
    wrapper.vm.search()
    await tick()
    expect(http.mikan).toHaveBeenCalledTimes(2)

    resolveSecond(validMikanList('第二个结果'))
    await tick()
    resolveFirst(validMikanList('过期第一个结果'))
    await tick()
    expect(wrapper.text()).toContain('第二个结果')
    expect(wrapper.text()).not.toContain('过期第一个结果')
  })

  it('still restores a Mikan list with score enrichment disabled', async () => {
    globalState.showScore.value = false
    http.mikan.mockResolvedValue(validMikanList('无评分列表'))
    wrapper = mount(MikanView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show({title: '无评分'})
    await tick()
    setHidden(true)
    await tick()
    setHidden(false)
    await tick()

    expect(http.mikan).toHaveBeenCalledTimes(1)
    expect(http.mikanScores).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无评分列表')
  })

  it('applies an AnimeGarden cold enrichment response and keeps its retry contract', async () => {
    globalState.showScore.value = true
    http.animeGardenList.mockResolvedValue(validAnimeGardenList('冷启动列表'))
    http.animeGardenEnrichment.mockResolvedValue({data: {
      subjects: {123: {cover: 'cover-after', score: 0}},
      retryableSubjectIds: []
    }})
    wrapper = mount(AnimeGardenView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show()
    await tick()

    expect(http.animeGardenEnrichment).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.data.items[0].subjects[0].cover).toBe('cover-after')
    expect(wrapper.vm.data.items[0].subjects[0].score).toBe(0)
  })

  it('keeps an expired AnimeGarden list behind the manual reload entry point', async () => {
    const expired = Object.assign(new Error('列表已过期'), {
      code: 'ANIME_GARDEN_LIST_EXPIRED', status: 409
    })
    http.animeGardenList
      .mockResolvedValueOnce(validAnimeGardenList('旧列表'))
    http.animeGardenEnrichment
      .mockRejectedValueOnce(expired)

    wrapper = mount(AnimeGardenView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show()
    for (let index = 0; index < 6; index++) await tick()

    expect(http.animeGardenList).toHaveBeenCalledTimes(1)
    expect(http.animeGardenEnrichment).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.listRecoveryState.status).toBe('failed')
    expect(wrapper.text()).toContain('列表已过期，请重新加载')
    expect(wrapper.text()).toContain('重新加载列表')
  })

  it('does not restart an expired AnimeGarden list on lifecycle resume', async () => {
    const expired = Object.assign(new Error('列表已过期'), {
      code: 'ANIME_GARDEN_LIST_EXPIRED', status: 409
    })
    http.animeGardenList.mockReset()
      .mockResolvedValueOnce(validAnimeGardenList('旧列表'))
    http.animeGardenEnrichment.mockReset().mockRejectedValueOnce(expired)

    wrapper = mount(AnimeGardenView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show()
    for (let index = 0; index < 8; index++) await tick()

    setHidden(true)
    await tick()
    setHidden(false)
    await tick()

    expect(http.animeGardenList).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.listRecoveryState.status).toBe('failed')
    expect(wrapper.find('[data-list-recovery-status]').text()).toContain('重新加载列表')
  })

  it('keeps an unknown AnimeGarden score unknown instead of converting it to zero', async () => {
    http.animeGardenList.mockResolvedValue(validAnimeGardenList('空评分列表'))
    http.animeGardenEnrichment.mockResolvedValue({data: {
      subjects: {123: {score: ''}},
      retryableSubjectIds: []
    }})
    wrapper = mount(AnimeGardenView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show()
    await tick()

    expect(wrapper.vm.data.items[0].subjects[0].score).toBeNull()
  })

  it('does not restart an active group request after collapse, but exposes a new request on re-open', async () => {
    let firstGroupSignal
    let resolveGroup
    http.animeGardenList.mockImplementation(() => Promise.resolve(validAnimeGardenList('可展开')))
    http.animeGardenGroup.mockImplementationOnce((_id, options) => {
      firstGroupSignal = options.signal
      return new Promise(resolve => { resolveGroup = resolve })
    }).mockImplementationOnce(() => Promise.resolve({data: []}))
    wrapper = mount(AnimeGardenView, {
      global: {stubs, directives: {loading: {}}}
    })
    wrapper.vm.show()
    await tick()
    wrapper.vm.collapseChange('123')
    await tick()
    expect(http.animeGardenGroup).toHaveBeenCalledTimes(1)
    const firstRequest = http.animeGardenGroup.mock.calls[0]
    wrapper.vm.collapseChange('')
    await tick()
    expect(firstRequest).toBeDefined()
    expect(firstGroupSignal.aborted).toBe(true)
    expect(wrapper.find('.group-content').exists()).toBe(false)
    wrapper.vm.collapseChange('123')
    await tick()
    expect(http.animeGardenGroup).toHaveBeenCalledTimes(2)
    resolveGroup?.({data: []})
  })
})
