import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import Mikan from './Mikan.vue'
import * as http from '@/js/http.js'
import {ElMessage} from 'element-plus'

vi.mock('@/js/http.js', () => ({
  mikan: vi.fn(),
  mikanScores: vi.fn(),
  mikanGroup: vi.fn(),
  rssToAni: vi.fn(),
  addAni: vi.fn()
}))
vi.mock('@/js/url.js', () => ({openHttpUrl: vi.fn()}))
vi.mock('@/other/SafeImage.vue', () => ({
  default: {template: '<img />'}
}))
vi.mock('element-plus', () => ({
  ElMessage: {error: vi.fn(), warning: vi.fn(), success: vi.fn()},
  ElText: {template: '<span><slot /></span>'}
}))

const deferred = () => {
  /** @type {(value: unknown) => void} */
  let resolve = () => {}
  const promise = new Promise(currentResolve => {
    resolve = currentResolve
  })
  return {promise, resolve}
}

const response = (seasonLabel, title, score) => ({
  data: {
    seasons: [
      {year: 2026, season: '春', seasonLabel: '2026 春', select: seasonLabel === '2026 春'},
      {year: 2026, season: '夏', seasonLabel: '2026 夏', select: seasonLabel === '2026 夏'}
    ],
    weeks: [{
      weekLabel: '星期一',
      items: [{
        url: `https://mikanani.me/Home/Bangumi/${score * 10}`,
        title,
        cover: '',
        score,
        exists: false
      }]
    }],
    totalItem: 1
  }
})

const stubs = {
  ElDialog: {template: '<div><slot /></div>'},
  ElCheckboxGroup: {template: '<div><slot /></div>'},
  ElInput: true,
  ElButton: true,
  ElSelect: {name: 'ElSelect', template: '<div><slot /></div>'},
  ElOption: true,
  ElScrollbar: {template: '<div><slot /></div>'},
  ElCollapse: {name: 'ElCollapse', template: '<div><slot /></div>'},
  ElCollapseItem: {
    props: ['name'],
    template: '<section :data-name="name"><slot name="title" /><slot /></section>'
  },
  ElText: {template: '<span><slot /></span>'},
  ElBadge: {template: '<span><slot /></span>'},
  ElCheckbox: true,
  ElCard: {template: '<div><slot /></div>'},
  ElProgress: true,
  ElRadioGroup: {template: '<div><slot /></div>'},
  ElRadio: {template: '<div><slot /></div>'},
  ElTag: {template: '<span><slot /></span>'}
}

describe('Mikan season changes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(http.mikanScores).mockResolvedValue({
      data: {scores: {}, subscribedBgmIds: []}
    })
  })

  it('renders the newest season score and ignores an older in-flight response', async () => {
    const initial = deferred()
    const spring = deferred()
    const summer = deferred()
    vi.mocked(http.mikan).mockImplementation((text, body) => {
      if (body?.seasonLabel === '2026 春') return spring.promise
      if (body?.seasonLabel === '2026 夏') return summer.promise
      return initial.promise
    })

    const wrapper = mount(Mikan, {
      global: {
        stubs,
        directives: {loading: {}}
      }
    })

    wrapper.vm.show()
    initial.resolve(response('2026 春', '初始季度', 7.1))
    await flushPromises()

    const select = wrapper.findComponent({name: 'ElSelect'})
    select.vm.$emit('change', '2026 春')
    select.vm.$emit('change', '2026 夏')

    summer.resolve(response('2026 夏', '夏季新番', 9.2))
    await flushPromises()
    spring.resolve(response('2026 春', '过期春季结果', 6.0))
    await flushPromises()

    expect(wrapper.text()).toContain('夏季新番')
    expect(wrapper.text()).toContain('9.2')
    expect(wrapper.text()).not.toContain('过期春季结果')
    expect(http.mikan).toHaveBeenCalledTimes(3)
    expect(ElMessage.warning).not.toHaveBeenCalled()
  })

  it('uses the backend totalItem field and enriches scores after rendering the list', async () => {
    const scores = deferred()
    vi.mocked(http.mikan).mockResolvedValue(response('2026 春', '先显示的番剧', 0))
    vi.mocked(http.mikanScores).mockReturnValue(scores.promise)

    const wrapper = mount(Mikan, {
      global: {
        stubs,
        directives: {loading: {}}
      }
    })

    wrapper.vm.show()
    await flushPromises()

    expect(wrapper.text()).toContain('先显示的番剧')
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(http.mikanScores).toHaveBeenCalledWith(['0'])

    scores.resolve({
      data: {
        scores: {'0': {bgmId: '42', score: 8.6}},
        subscribedBgmIds: ['42']
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('8.6')
  })
})
