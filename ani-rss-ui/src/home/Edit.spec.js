import {flushPromises, shallowMount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import Edit from './Edit.vue'
import * as http from '@/js/http.js'

const messageBox = vi.hoisted(() => ({confirm: vi.fn()}))

vi.mock('@/js/http.js', () => ({
  downloadPath: vi.fn(),
  setAni: vi.fn()
}))

vi.mock('element-plus', () => ({
  ElMessage: {success: vi.fn()},
  ElMessageBox: messageBox
}))

const AniStub = {name: 'Ani', template: '<div />'}
const DialogStub = {name: 'ElDialog', template: '<div><slot /></div>'}
const ButtonStub = {name: 'ElButton', emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>'}

const mountEditor = () => shallowMount(Edit, {
  global: {
    stubs: {
      Ani: AniStub,
      ElDialog: DialogStub,
      ElButton: ButtonStub,
      ElText: true
    }
  }
})

describe('edit subscription completion', () => {
  beforeEach(() => {
    window.$reLoadList = vi.fn()
    messageBox.confirm.mockReset()
  })

  it('releases the child loading state when previewing the download path fails', async () => {
    vi.mocked(http.downloadPath).mockRejectedValue(new Error('preview failed'))
    const wrapper = mountEditor()
    wrapper.vm.show({id: 'subscription-1'})
    await flushPromises()

    const done = vi.fn()
    wrapper.findComponent({name: 'Ani'}).vm.$emit('callback', done)
    await flushPromises()

    expect(done).toHaveBeenCalledTimes(1)
  })

  it('releases the child loading state when a move confirmation is cancelled', async () => {
    vi.mocked(http.downloadPath).mockResolvedValue({data: {downloadPath: '/downloads', change: true}})
    messageBox.confirm.mockRejectedValue('cancel')
    const wrapper = mountEditor()
    wrapper.vm.show({id: 'subscription-1'})
    await flushPromises()

    const done = vi.fn()
    wrapper.findComponent({name: 'Ani'}).vm.$emit('callback', done)
    await flushPromises()

    const buttons = wrapper.findAllComponents({name: 'ElButton'})
    expect(buttons).toHaveLength(2)
    buttons[0].vm.$emit('click')
    await flushPromises()

    expect(http.setAni).not.toHaveBeenCalled()
    expect(done).toHaveBeenCalledTimes(1)
  })
})
