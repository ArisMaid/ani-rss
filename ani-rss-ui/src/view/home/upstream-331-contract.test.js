// @vitest-environment jsdom

import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {nextTick} from 'vue'
import {mount} from '@vue/test-utils'
import {getLabel, notificationTypeList} from '@/js/notification-type.js'
import {configData} from '@/js/config.js'

const http = vi.hoisted(() => ({
  config: vi.fn(),
  newNotification: vi.fn()
}))

const router = vi.hoisted(() => ({
  push: vi.fn()
}))

const routeState = vi.hoisted(() => ({
  path: '/home',
  query: {}
}))

vi.mock('@/js/http.js', () => http)
vi.mock('@/js/global.js', () => ({initLayout: vi.fn()}))
vi.mock('vue-router', () => ({
  RouterView: {template: '<div class="router-view-stub" />'},
  useRoute: () => routeState,
  useRouter: () => router
}))
vi.mock('./DefaultLoginDialogView.vue', () => ({
  __esModule: true,
  default: {
    props: ['modelValue', 'hideReminder'],
    emits: ['update:modelValue', 'update:hideReminder', 'confirm'],
    template: `<div class="default-login-dialog-stub">
      <button class="confirm-btn" @click="$emit('confirm')">立即修改</button>
    </div>`
  }
}))

import MainLayoutView from './MainLayoutView.vue'
import NotificationView from '@/view/config/NotificationView.vue'

const tick = async () => {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

describe('upstream v3.3.1 frontend contracts', () => {
  let wrapper

  beforeEach(() => {
    vi.stubGlobal('localStorage', jsdom.window.localStorage)
    window.localStorage.clear()
    routeState.path = '/home'
    routeState.query = {}
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  it('shows default login reminder only when backend reports isDefault and navigates to login tab', async () => {
    http.config.mockResolvedValue({data: {login: {username: 'admin', password: '', isDefault: true}}})
    wrapper = mount(MainLayoutView, {
      global: {
        stubs: {
          'el-menu': {template: '<div><slot/></div>'},
          'el-menu-item': {template: '<div><slot/></div>'},
          'el-icon': {template: '<span><slot/></span>'}
        }
      }
    })
    for (let i = 0; i < 6; i++) await tick()

    expect(wrapper.find('.default-login-dialog-stub').exists()).toBe(true)
    await wrapper.find('.confirm-btn').trigger('click')
    expect(router.push).toHaveBeenCalledWith({path: '/settings', query: {tab: 'login'}})
  })

  it('skips default login config check when user disabled the reminder', async () => {
    window.localStorage.setItem('hide-default-login-reminder', 'true')
    wrapper = mount(MainLayoutView, {
      global: {
        stubs: {
          'el-menu': {template: '<div><slot/></div>'},
          'el-menu-item': {template: '<div><slot/></div>'},
          'el-icon': {template: '<span><slot/></span>'}
        }
      }
    })
    await tick()

    expect(http.config).not.toHaveBeenCalled()
    expect(wrapper.find('.default-login-dialog-stub').exists()).toBe(false)
  })

  it('exposes AstrBot notification type and toggles notification enable state', async () => {
    expect(configData.offsetType).toBe('AUTO_OFFSET')
    expect(configData.login.isDefault).toBe(false)
    expect(notificationTypeList.some(item => item.name === 'ASTRBOT')).toBe(true)
    expect(getLabel('ASTRBOT')).toBe('AstrBot')

    const config = {
      notificationTemplate: '${text}',
      notificationConfigList: [
        {notificationType: 'ASTRBOT', comment: '群推送', enable: true}
      ]
    }
    wrapper = mount(NotificationView, {
      props: {config},
      global: {
        stubs: {
          'el-collapse': {template: '<div><slot/></div>'},
          'el-collapse-item': {template: '<div><slot/></div>'},
          'el-input': {template: '<input />'},
          'el-link': {template: '<a />'},
          'el-card': {template: '<div class="card"><slot/></div>'},
          'el-tooltip': {template: '<span><slot/></span>'},
          'el-text': {template: '<span><slot/></span>'},
          'el-dropdown': {template: '<div><slot/><slot name="dropdown"/></div>'},
          'el-dropdown-menu': {template: '<div><slot/></div>'},
          'el-dropdown-item': {emits: ['click'], template: '<button class="menu-item" @click="$emit(\'click\')"><slot/></button>'},
          'el-button': {template: '<button><slot/></button>'},
          'el-icon': {template: '<span><slot/></span>'},
          NotificationConfigView: {template: '<div />'}
        }
      }
    })

    expect(wrapper.find('.notification-status.is-enabled').exists()).toBe(true)
    await wrapper.find('.menu-item').trigger('click')
    expect(config.notificationConfigList[0].enable).toBe(false)
    expect(wrapper.find('.notification-card-disabled').exists()).toBe(true)
  })
})
