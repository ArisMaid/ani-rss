import {flushPromises, shallowMount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import Ownership from './Ownership.vue'
import * as http from '@/js/http.js'

vi.mock('@/js/http.js', () => ({
  listOwnershipCandidates: vi.fn(),
  listOwnerships: vi.fn(),
  listQuarantine: vi.fn(),
  listSubscriptionsV2: vi.fn(),
  adoptOwnership: vi.fn(),
  restoreQuarantine: vi.fn(),
  purgeExpiredQuarantine: vi.fn(),
  purgeQuarantine: vi.fn()
}))

describe('Ownership management', () => {
  beforeEach(() => {
    vi.mocked(http.listOwnershipCandidates).mockResolvedValue({data: []})
    vi.mocked(http.listOwnerships).mockResolvedValue({data: []})
    vi.mocked(http.listQuarantine).mockResolvedValue({data: []})
    vi.mocked(http.listSubscriptionsV2).mockResolvedValue({data: []})
  })

  it('loads every ownership safety view when mounted', async () => {
    const wrapper = shallowMount(Ownership, {
      global: {
        directives: {loading: {}},
        stubs: {
          ElButton: true,
          ElPopconfirm: true,
          ElTabs: true,
          ElTabPane: true,
          ElTable: true,
          ElTableColumn: true,
          ElText: true,
          ElSelect: true,
          ElOption: true,
          ElTag: true,
          ElEmpty: true
        }
      }
    })

    await flushPromises()

    expect(wrapper.exists()).toBe(true)
    expect(http.listOwnershipCandidates).toHaveBeenCalledOnce()
    expect(http.listOwnerships).toHaveBeenCalledOnce()
    expect(http.listQuarantine).toHaveBeenCalledOnce()
    expect(http.listSubscriptionsV2).toHaveBeenCalledOnce()
  })
})
