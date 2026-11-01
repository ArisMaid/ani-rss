// @vitest-environment jsdom
import { expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import UpgradeView from './UpgradeView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(async () => ({})) }))
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
