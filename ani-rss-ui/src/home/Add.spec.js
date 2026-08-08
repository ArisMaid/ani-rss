import {flushPromises, shallowMount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'
import Add from './Add.vue'
import * as http from '@/js/http.js'

vi.mock('@/js/http.js', () => ({
  rssToAni: vi.fn(),
  addAni: vi.fn()
}))

const mikanPreload = vi.hoisted(() => vi.fn())

const providerStub = name => ({
  name,
  methods: {preload: mikanPreload},
  template: '<div />'
})

describe('add subscription provider handoff', () => {
  beforeEach(() => {
    mikanPreload.mockReset()
    vi.mocked(http.rssToAni).mockResolvedValue({
      data: {title: 'Example', url: 'https://feed.example.test/rss', bgmUrl: 'https://bgm.tv/subject/1'}
    })
    vi.mocked(http.addAni).mockResolvedValue({message: 'ok'})
  })

  it('accepts a provider Ref payload and advances to the Ani step', async () => {
    const wrapper = shallowMount(Add, {
      global: {
        stubs: {
          Mikan: providerStub('Mikan'),
          AniBT: providerStub('AniBT'),
          AnimeGarden: providerStub('AnimeGarden'),
          Bgm: providerStub('Bgm'),
          Ani: providerStub('Ani'),
          ElDialog: {template: '<div><slot /></div>'},
          ElTabs: {template: '<div><slot /></div>'},
          ElTabPane: {template: '<div><slot /></div>'},
          ElForm: {template: '<form><slot /></form>'},
          ElFormItem: {template: '<div><slot /></div>'},
          ElInput: true,
          ElButton: true,
          ElText: true
        }
      }
    })

    const selection = ref({
      subgroup: 'Example Fansub',
      match: '["720p"]',
      url: 'https://feed.example.test/rss',
      bgmUrl: 'https://bgm.tv/subject/1'
    })
    wrapper.findComponent({name: 'Mikan'}).vm.$emit('callback', selection)
    await flushPromises()

    expect(http.rssToAni).toHaveBeenCalledWith(expect.objectContaining({
      url: selection.value.url,
      bgmUrl: selection.value.bgmUrl,
      subgroup: selection.value.subgroup,
      match: ['{{Example Fansub}}:720p'],
      type: 'mikan'
    }))
  })

  it('does not crash on a malformed match list', async () => {
    const wrapper = shallowMount(Add, {
      global: {
        stubs: {
          Mikan: providerStub('Mikan'),
          AniBT: providerStub('AniBT'),
          AnimeGarden: providerStub('AnimeGarden'),
          Bgm: providerStub('Bgm'),
          Ani: providerStub('Ani'),
          ElDialog: {template: '<div><slot /></div>'},
          ElTabs: {template: '<div><slot /></div>'},
          ElTabPane: {template: '<div><slot /></div>'},
          ElForm: {template: '<form><slot /></form>'},
          ElFormItem: {template: '<div><slot /></div>'},
          ElInput: true,
          ElButton: true,
          ElText: true
        }
      }
    })

    wrapper.findComponent({name: 'Mikan'}).vm.$emit('callback', {
      subgroup: 'Example Fansub',
      match: 'not-json',
      url: 'https://feed.example.test/rss',
      bgmUrl: 'https://bgm.tv/subject/1'
    })
    await flushPromises()

    expect(http.rssToAni).toHaveBeenCalledWith(expect.objectContaining({
      match: [],
      type: 'mikan'
    }))
  })

  it('warms the Mikan cache after the Add dialog opens', async () => {
    const wrapper = shallowMount(Add, {
      global: {
        stubs: {
          Mikan: providerStub('Mikan'),
          AniBT: providerStub('AniBT'),
          AnimeGarden: providerStub('AnimeGarden'),
          Bgm: providerStub('Bgm'),
          Ani: providerStub('Ani'),
          ElDialog: {name: 'ElDialog', template: '<div><slot /></div>'},
          ElTabs: {template: '<div><slot /></div>'},
          ElTabPane: {template: '<div><slot /></div>'},
          ElForm: {template: '<form><slot /></form>'},
          ElFormItem: {template: '<div><slot /></div>'},
          ElInput: true,
          ElButton: true,
          ElText: true
        }
      }
    })

    wrapper.vm.show()
    await wrapper.findComponent({name: 'ElDialog'}).vm.$emit('opened')

    expect(mikanPreload).toHaveBeenCalledOnce()
  })
})
