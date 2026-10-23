// @vitest-environment jsdom
import {afterEach, describe, expect, it, vi} from 'vitest'
import {mount, flushPromises} from '@vue/test-utils'
const http = vi.hoisted(() => ({rssToAni:vi.fn(),addAni:vi.fn()}))
const confirm = vi.hoisted(() => vi.fn())
vi.mock('@/js/http.js', () => http)
vi.mock('element-plus', () => ({ElMessage:{error:vi.fn(),success:vi.fn()},ElMessageBox:{confirm}}))
vi.mock('./AniView.vue', () => ({default:{props:['ani'],emits:['callback'],template:'<div><input aria-label="edited-feed" v-model="ani.url"/><button class="confirm-item" @click="$emit(\'callback\',()=>{})">确认</button></div>'}}))
import BatchAdditionView from './BatchAdditionView.vue'
const pass = {template:'<div><slot name="label"/><slot/></div>'}
const dialog = {props:['modelValue'],template:'<div v-if="modelValue"><slot/></div>'}
const button = {template:'<button><slot/></button>'}
const requests = [{title:'One',url:'https://example.test/one',match:['first']},{title:'Two',url:'https://example.test/two',match:[]}]
const deferred = () => {let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
describe('batch subscription confirmation', () => {
  let wrapper
  afterEach(()=>{wrapper?.unmount();vi.resetAllMocks()})
  const create = () => wrapper=mount(BatchAdditionView,{props:{modelValue:true,type:'other'},global:{directives:{loading:()=>{}},stubs:{'el-dialog':dialog,'el-tabs':pass,'el-tab-pane':pass,'el-tag':pass,'el-alert':{props:['title'],template:'<div>{{title}}</div>'},'el-button':button}}})
  it('parses sequentially and posts only after confirmation, preserving edited RSS', async () => {
    const pending=deferred();http.rssToAni.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({data:{title:'Two',url:'server-two'}});http.addAni.mockResolvedValue({})
    create();const loading=wrapper.vm.openRequests(requests);await flushPromises()
    expect(http.rssToAni).toHaveBeenCalledTimes(1);expect(http.addAni).not.toHaveBeenCalled()
    pending.resolve({data:{title:'One',url:'server-one',match:['wrong']}});await loading;await flushPromises()
    expect(http.rssToAni).toHaveBeenCalledTimes(2)
    await wrapper.find('input').setValue('https://example.test/edited')
    await wrapper.find('.confirm-item').trigger('click');await flushPromises()
    expect(http.addAni.mock.calls[0][0]).toMatchObject({url:'https://example.test/edited',match:['first']})
    expect(wrapper.emitted('done')).toBeUndefined()
  })
  it('continues after a failed entry and retries only that entry', async () => {
    http.rssToAni.mockRejectedValueOnce(new Error('Source unavailable')).mockResolvedValueOnce({data:{title:'Two'}}).mockResolvedValueOnce({data:{title:'One'}})
    create();await wrapper.vm.openRequests(requests);await flushPromises()
    expect(wrapper.text()).toContain('Source unavailable');expect(wrapper.findAll('.confirm-item')).toHaveLength(1)
    await wrapper.find('button').trigger('click');await flushPromises()
    expect(http.rssToAni).toHaveBeenCalledTimes(3);expect(wrapper.findAll('.confirm-item')).toHaveLength(2)
  })
  it('cancels parsing on close and ignores a late source response', async () => {
    const pending=deferred();http.rssToAni.mockReturnValue(pending.promise)
    create();const loading=wrapper.vm.openRequests(requests);await flushPromises()
    const signal=http.rssToAni.mock.calls[0][1].signal;await wrapper.setProps({modelValue:false})
    expect(signal.aborted).toBe(true)
    pending.resolve({data:{title:'Stale'}});await loading;await flushPromises()
    expect(http.rssToAni).toHaveBeenCalledTimes(1);expect(wrapper.find('.confirm-item').exists()).toBe(false)
  })
  it('requires confirmation before overriding and cancellation does not retry the write', async () => {
    http.rssToAni.mockResolvedValue({data:{title:'Existing',url:'https://example.test/feed'}})
    http.addAni.mockRejectedValueOnce(Object.assign(new Error('Exists'),{code:'SUBSCRIPTION_EXISTS'}));confirm.mockRejectedValue('cancel')
    create();await wrapper.vm.openRequests([requests[0]]);await flushPromises()
    await wrapper.find('.confirm-item').trigger('click');await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(1);expect(http.addAni).toHaveBeenCalledTimes(1);expect(wrapper.find('.confirm-item').exists()).toBe(true)
    http.addAni.mockRejectedValueOnce(Object.assign(new Error('Exists'),{code:'SUBSCRIPTION_EXISTS'})).mockResolvedValueOnce({});confirm.mockResolvedValue('confirm')
    await wrapper.find('.confirm-item').trigger('click');await flushPromises()
    expect(http.addAni.mock.calls[2][1]).toBe(true);expect(wrapper.emitted('done')).toHaveLength(1)
  })
})
