import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import List from './List.vue'
import * as http from '@/js/http.js'

const dialogShows = vi.hoisted(() => ({
  edit: vi.fn(), playlist: vi.fn(), cover: vi.fn(), del: vi.fn(), rate: vi.fn()
}))

vi.mock('@/js/http.js', () => ({listAni: vi.fn()}))
vi.mock('@/js/global.js', () => ({showWeek: {value: true}}))

vi.mock('./AniCard.vue', () => ({
  __esModule: true,
  default: {
    props: ['item'],
    template: `<div>
      <button data-testid="edit" @click="$emit('edit', item)" />
      <button data-testid="playlist" @click="$emit('playlist', item)" />
      <button data-testid="cover" @click="$emit('cover', item)" />
      <button data-testid="del" @click="$emit('del', [item])" />
      <button data-testid="rate" @click="$emit('rate', item)" />
    </div>`
  }
}))

vi.mock('./Edit.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.edit}, template: '<div />'}
}))
vi.mock('@/play/PlayList.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.playlist}, template: '<div />'}
}))
vi.mock('./Cover.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.cover}, template: '<div />'}
}))
vi.mock('./Del.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.del}, template: '<div />'}
}))
vi.mock('./BgmRate.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.rate}, template: '<div />'}
}))

describe('list lazy dialogs', () => {
  beforeEach(() => {
    vi.mocked(http.listAni).mockResolvedValue({data: {
      weekList: [{weekLabel: '星期一', items: [{id: '1', title: 'Example', pinyin: '', pinyinInitials: [],
        enable: true, releaseDate: '2026-01', sort: 0, lastDownloadTime: 0}]}],
      releaseDateList: []
    }})
  })

  it('opens every card dialog on its first action', async () => {
    const wrapper = mount(List, {
      props: {title: '', filter: () => true},
      global: {
        stubs: {
          ElScrollbar: {template: '<div><slot /></div>'},
          ElText: true,
          ElTooltip: true,
          ElTag: true,
          ElButton: true,
          ElIcon: true,
          ElCard: true
        },
        directives: {loading: {}}
      }
    })
    await flushPromises()

    for (const [name, show] of Object.entries({
      edit: dialogShows.edit,
      playlist: dialogShows.playlist,
      cover: dialogShows.cover,
      del: dialogShows.del,
      rate: dialogShows.rate
    })) {
      await wrapper.get(`[data-testid="${name}"]`).trigger('click')
      await flushPromises()
      expect(show).toHaveBeenCalledOnce()
    }
  })
})
