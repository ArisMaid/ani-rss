import {flushPromises, shallowMount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import Del from './Del.vue'
import * as http from '@/js/http.js'

vi.mock('@/js/http.js', () => ({
  deleteSubscriptions: vi.fn()
}))

const messages = vi.hoisted(() => ({success: vi.fn(), warning: vi.fn(), error: vi.fn()}))

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
      ElCheckbox: {
        name: 'ElCheckbox',
        props: ['modelValue'],
        emits: ['update:modelValue'],
        template: '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" /><slot /></label>'
      },
      ElText: true,
      ElAlert: true
    }
  }
})

describe('direct subscription deletion', () => {
  beforeEach(() => {
    window.$reLoadList = vi.fn()
    messages.success.mockReset()
    messages.warning.mockReset()
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
    expect(http.deleteSubscriptions).toHaveBeenCalledWith(['subscription-1'], true)
    expect(window.$reLoadList).toHaveBeenCalledOnce()
  })

  it('keeps local files when the checkbox is cleared', async () => {
    vi.mocked(http.deleteSubscriptions).mockResolvedValue({
      data: {deletedSubscriptions: 1, deletedRemoteTasks: 1, deletedFiles: 0, skippedFiles: 0}
    })
    const wrapper = mountDialog()
    wrapper.vm.show([{id: 'subscription-1', title: 'Example', season: 1}])

    await wrapper.find('input[type="checkbox"]').setValue(false)
    wrapper.findComponent({name: 'ElButton'}).vm.$emit('click')
    await flushPromises()

    expect(http.deleteSubscriptions).toHaveBeenCalledWith(['subscription-1'], false)
  })
})
