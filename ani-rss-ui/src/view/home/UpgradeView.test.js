// @vitest-environment jsdom
import { expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import UpgradeView from './UpgradeView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(async () => ({})), post: vi.fn() }))
vi.mock('@/js/api.js', () => ({ default: api }))
vi.mock('element-plus', () => ({ ElMessage: {}, ElMessageBox: {} }))
vi.mock('@/view/custom/PageHeaderView.vue', () => ({ default: { template: '<div/>' } }))
vi.mock('@/view/custom/SettingsItem.vue', () => ({ default: { template: '<div/>' } }))

it('keeps rows and protection actions on the selected series when earlier requests finish last', async () => {
  const delayed = []
  api.get.mockImplementation(async path => {
    const id = path.match(/targetId=([AB])|targets\/([AB])\/coverage/)?.slice(1).find(Boolean)
    if (id) {
      if (id === 'A') await new Promise(resolve => delayed.push(resolve))
      const data = path.includes('/coverage') ? { id, expectedChapters: [], missingChapters: [] }
        : { items: [{ id: id + (path.includes('/versions') ? '-version' : '-release'), targetId: id, active: true }] }
      return { data }
    }
    if (path === '/api/v2/config') return { data: { upgrade: { libraryIds: [] } } }
    if (path.includes('policy-presets')) return { data: { groups: [], presets: [] } }
    return { data: [] }
  })
  const wrapper = shallowMount(UpgradeView, { global: { config: { warnHandler() {} }, stubs: { 'el-tabs': true, 'el-dialog': true } } })
  try {
    await flushPromises()
    const state = wrapper.vm.$.setupState
    const earlier = state.showCandidates({ id: 'A' })
    await state.showCandidates({ id: 'B' })
    delayed.forEach(resolve => resolve())
    await earlier
    expect(state.versionPage.items[0].id).toBe('B-version')
    expect(state.candidatePage.items[0].id).toBe('B-release')
    expect(state.targetCoverage.id).toBe('B')
    await state.protect({ id: 'A-version', targetId: 'A' }, true)
    expect(api.put).not.toHaveBeenCalled()
    await state.protect(state.versionPage.items[0], true)
    expect(api.put).toHaveBeenCalledWith('/api/v2/upgrade/versions/B-version/protection', { protectedVersion: true })
  } finally {
    wrapper.unmount()
  }
})

it('uses the edited AI connection, suppresses repeat probes and discards results for previous credentials or models', async () => {
  api.get.mockImplementation(async path => ({data: path === '/api/v2/config'
    ? {upgrade: {libraryIds: [], aiBaseUrl: 'https://fixture.example', aiApiKey: '', aiModel: '', clearAiKey: false}}
    : []}))
  const wrapper = shallowMount(UpgradeView, { global: { config: { warnHandler() {} }, stubs: { 'el-tabs': true, 'el-dialog': true } } })
  try {
    await flushPromises()
    const state = wrapper.vm.$.setupState
    state.settings.aiApiKey = 'fixture'
    let finish
    api.post.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const first = state.probeAi('models')
    await state.probeAi('models')
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledWith('/api/v2/upgrade/ai/models', {
      aiBaseUrl: 'https://fixture.example', aiApiKey: 'fixture', aiModel: '', clearAiKey: false
    }, {silent: true})
    state.settings.aiApiKey = 'replacement'
    finish({data: {data: [{id: 'stale'}]}})
    await first
    expect(state.modelRows).toEqual([])
    state.settings.aiModel = 'manual'
    api.post.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const previous = state.probeAi('test')
    state.settings.aiModel = 'replacement-model'
    finish({data: {success: true, model: 'manual'}})
    await previous
    expect(state.connectionTested).toBeFalsy()
    api.post.mockResolvedValueOnce({data: {success: true, model: 'replacement-model'}})
    await state.probeAi('test')
    expect(state.connectionTested).toBe(true)
    expect(state.settings.aiApiKey).toBe('replacement')
    expect(state.detailDialog).toBe(false)
    state.settings.clearAiKey = true
    expect(state.connectionTested).toBeFalsy()
    expect(state.aiReady).toBeFalsy()
  } finally { wrapper.unmount() }
})
