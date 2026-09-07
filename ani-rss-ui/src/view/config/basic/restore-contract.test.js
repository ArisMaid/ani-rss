// @vitest-environment jsdom

import {afterEach, describe, expect, it, vi} from 'vitest'
import {nextTick} from 'vue'
import {mount} from '@vue/test-utils'

const http = vi.hoisted(() => ({
  __v_isRef: false,
  stageRestore: vi.fn(),
  confirmRestore: vi.fn(),
  restoreStatus: vi.fn()
}))
const messages = vi.hoisted(() => ({
  error: vi.fn(),
  warning: vi.fn(),
  success: vi.fn()
}))

vi.mock('@/js/http.js', () => http)
vi.mock('element-plus', () => ({ElMessage: messages}))

import BackupView from './BackupView.vue'

const UploadStub = {
  name: 'UploadStub',
  props: ['callback'],
  template: '<div class="upload-stub"/>'
}

const dialog = {
  props: ['modelValue'],
  emits: ['closed'],
  template: `<div v-if="modelValue" class="restore-dialog">
    <slot/>
    <slot name="footer"/>
  </div>`
}

const button = {
  props: ['disabled', 'loading'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>'
}

const simple = {
  props: ['title', 'label'],
  template: '<div><span v-if="title">{{ title }}</span><span v-if="label">{{ label }}</span><slot/></div>'
}

const tick = async () => {
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

const stage = (wrapper, value) => wrapper.findComponent(UploadStub).vm.callback(value)

describe('restore UI contracts', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  const mountView = () => mount(BackupView, {
    global: {
      stubs: {
        UploadView: UploadStub,
        'el-dialog': dialog,
        'el-button': button,
        'el-alert': simple,
        'el-descriptions': simple,
        'el-descriptions-item': simple
      }
    }
  })

  const confirmButton = () => wrapper.findAll('button').find(item => item.text().includes('确认覆盖'))
  const queryButton = () => wrapper.findAll('button').find(item => item.text().includes('重新查询'))

  it('does not allow an INVALID staged archive to be confirmed', async () => {
    wrapper = mountView()
    stage(wrapper, {operationId: 'invalid-op', status: 'INVALID', errors: ['manifest is invalid']})
    await tick()

    expect(wrapper.text()).toContain('manifest is invalid')
    expect(confirmButton().element.disabled).toBe(true)
    expect(http.confirmRestore).not.toHaveBeenCalled()
  })

  it('confirms an operation once and removes the persisted id only at SUCCEEDED', async () => {
    let resolveConfirm
    http.confirmRestore.mockImplementation(() => new Promise(resolve => { resolveConfirm = resolve }))
    http.restoreStatus.mockResolvedValue({operationId: 'restore-op', status: 'SUCCEEDED'})
    wrapper = mountView()
    stage(wrapper, {operationId: 'restore-op', status: 'VALIDATED', files: []})
    await tick()

    const buttonElement = confirmButton()
    await buttonElement.trigger('click')
    await buttonElement.trigger('click')
    expect(http.confirmRestore).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem('ani-rss.restore-operation')).toBe(null)

    resolveConfirm({operationId: 'restore-op', status: 'QUEUED'})
    await tick()
    await tick()

    expect(http.restoreStatus).toHaveBeenCalledWith('restore-op')
    expect(messages.success).toHaveBeenCalledWith('导入完成，请重新登录')
    expect(sessionStorage.getItem('ani-rss.restore-operation')).toBe(null)
  })

  it('keeps the operation id after a polling error and exposes a re-query action', async () => {
    http.confirmRestore.mockResolvedValue({operationId: 'retry-op', status: 'QUEUED'})
    http.restoreStatus
      .mockRejectedValueOnce(Object.assign(new Error('temporary outage'), {status: 503}))
      .mockResolvedValueOnce({operationId: 'retry-op', status: 'SUCCEEDED'})
    wrapper = mountView()
    stage(wrapper, {operationId: 'retry-op', status: 'VALIDATED', files: []})
    await tick()
    await confirmButton().trigger('click')
    await tick()
    await tick()

    expect(sessionStorage.getItem('ani-rss.restore-operation')).toBe('retry-op')
    expect(wrapper.text()).toContain('temporary outage')
    expect(queryButton()).toBeDefined()

    await queryButton().trigger('click')
    await tick()
    await tick()

    expect(http.restoreStatus).toHaveBeenCalledTimes(2)
    expect(messages.success).toHaveBeenCalledWith('导入完成，请重新登录')
    expect(sessionStorage.getItem('ani-rss.restore-operation')).toBe(null)
  })
})
