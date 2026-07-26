import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import App from './App.vue'
import * as http from '@/js/http.js'

const dialogShows = vi.hoisted(() => ({
  add: vi.fn(),
  collection: vi.fn(),
  config: vi.fn(),
  logs: vi.fn(),
  manage: vi.fn(),
  torrents: vi.fn()
}))

vi.mock('@/js/http.js', () => ({
  about: vi.fn(),
  refreshAll: vi.fn()
}))

vi.mock('@/js/global.js', () => ({
  elIconClass: '',
  initLayout: vi.fn(),
  isNotMobile: true
}))

vi.mock('./Add.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.add}, render: () => null}
}))
vi.mock('./Collection.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.collection}, render: () => null}
}))
vi.mock('./Config.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.config}, render: () => null}
}))
vi.mock('./Logs.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.logs}, render: () => null}
}))
vi.mock('./Manage.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.manage}, render: () => null}
}))
vi.mock('./TorrentsInfos.vue', () => ({
  __esModule: true,
  default: {methods: {show: dialogShows.torrents}, render: () => null}
}))

const passthrough = {template: '<div><slot/><slot name="dropdown"/></div>'}
const clickable = {template: '<button><slot/></button>'}

describe('home toolbar lazy dialogs', () => {
  beforeEach(() => {
    vi.mocked(http.about).mockResolvedValue({
      data: {version: '3.1.75.15', latest: '3.1.75', update: true, markdownBody: ''}
    })
    vi.mocked(http.refreshAll).mockResolvedValue({message: 'ok'})
  })

  it('opens every dialog on its first toolbar action', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          ElBadge: passthrough,
          ElButton: clickable,
          ElDropdown: passthrough,
          ElDropdownItem: clickable,
          ElDropdownMenu: passthrough,
          ElIcon: passthrough,
          ElInput: true,
          ElOption: true,
          ElSelect: true,
          List: {
            data: () => ({releaseDateList: []}),
            methods: {changeFilterList: vi.fn()},
            render: () => null
          },
          Popconfirm: passthrough
        }
      }
    })

    await flushPromises()

    for (const [action, show] of [
      ['add', dialogShows.add],
      ['collection', dialogShows.collection],
      ['torrents', dialogShows.torrents],
      ['manage', dialogShows.manage],
      ['config', dialogShows.config],
      ['logs', dialogShows.logs]
    ]) {
      await wrapper.get(`[data-testid="open-${action}"]`).trigger('click')
      await flushPromises()
      expect(show).toHaveBeenCalledOnce()
    }

    expect(dialogShows.config).toHaveBeenCalledWith(true)
  })
})
