import {flushPromises, shallowMount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import Del from './Del.vue'
import * as http from '@/js/http.js'

vi.mock('@/js/http.js', () => ({
  deleteSubscriptions: vi.fn()
}))

const messages = vi.hoisted(() => ({success: vi.fn(), error: vi.fn()}))

vi.mock('element-plus', () => ({
  ElMessage: messages
}))

const DialogStub = {name: 'ElDialog', template: '<div><slot /></div>'}
const ButtonStub = {
  name: 'ElButton',
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot /></button>'
}

const mountDialog = () => shallowMount(Del, {
  global: {
    stubs: {
      ElDialog: DialogStub,
      ElButton: ButtonStub,
      ElText: true,
      ElAlert: true
    }
  }
})

describe('direct subscription deletion', () => {
  beforeEach(() => {
    window.$reLoadList = vi.fn()
    messages.success.mockReset()
    messages.error.mockReset()
    vi.mocked(http.deleteSubscriptions).mockReset()
  })

  it('deletes selected subscriptions immediately without a plan request', async () => {
    vi.mocked(http.deleteSubscriptions).mockResolvedValue({
      data: {deletedSubscriptions: 1, deletedRemoteTasks: 1, deletedFiles: 1}
    })
    const wrapper = mountDialog()
    wrapper.vm.show([{id: 'subscription-1', title: 'Example', season: 1}])

    wrapper.findComponent({name: 'ElButton'}).vm.$emit('click')
    await flushPromises()

    expect(http.deleteSubscriptions).toHaveBeenCalledOnce()
    expect(http.deleteSubscriptions).toHaveBeenCalledWith(['subscription-1'])
    expect(window.$reLoadList).toHaveBeenCalledOnce()
  })
})
