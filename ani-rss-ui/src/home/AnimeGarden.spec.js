import {flushPromises, mount} from '@vue/test-utils'
import {describe, expect, it, vi} from 'vitest'
import AnimeGarden from './AnimeGarden.vue'
import * as http from '@/js/http.js'

vi.mock('@/js/http.js', () => ({
  animeGardenList: vi.fn(),
  animeGardenGroup: vi.fn(),
  addAni: vi.fn()
}))
vi.mock('@/js/url.js', () => ({openHttpUrl: vi.fn()}))
vi.mock('@/other/SafeImage.vue', () => ({
  default: {template: '<img />'}
}))
vi.mock('@element-plus/icons-vue', () => ({
  DocumentCopy: {template: '<span />'}
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

const response = (title, score) => ({
  data: [{
    weekLabel: 'Monday',
    subjects: [{
      id: `subject-${score}`,
      name: title,
      cover: '',
      score,
      exists: false
    }]
  }]
})

const stubs = {
  ElDialog: {template: '<div><slot /></div>'},
  ElCheckboxGroup: {template: '<div><slot /></div>'},
  ElButton: true,
  ElScrollbar: {template: '<div><slot /></div>'},
  ElCollapse: {template: '<div><slot /></div>'},
  ElCollapseItem: {template: '<section><slot name="title" /><slot /></section>'},
  ElText: {template: '<span><slot /></span>'},
  ElBadge: {template: '<span><slot /></span>'},
  ElCheckbox: true,
  ElCard: {template: '<div><slot /></div>'},
  ElProgress: true,
  ElRadioGroup: {template: '<div><slot /></div>'},
  ElRadio: {template: '<div><slot /></div>'},
  ElTag: {template: '<span><slot /></span>'}
}

describe('AnimeGarden list requests', () => {
  it('keeps the newest list when an earlier request resolves late', async () => {
    const first = deferred()
    const second = deferred()
    vi.mocked(http.animeGardenList)
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise)

    const wrapper = mount(AnimeGarden, {
      global: {
        stubs,
        directives: {loading: {}}
      }
    })

    wrapper.vm.show()
    wrapper.vm.show()

    second.resolve(response('Newest AnimeGarden result', 8.8))
    await flushPromises()
    first.resolve(response('Stale AnimeGarden result', 6.2))
    await flushPromises()

    expect(wrapper.text()).toContain('Newest AnimeGarden result')
    expect(wrapper.text()).toContain('8.8')
    expect(wrapper.text()).not.toContain('Stale AnimeGarden result')
  })
})
