<template>
  <el-dialog v-model="visible" title="批量添加订阅" width="min(860px, calc(100vw - 24px))" align-center
             :close-on-click-modal="false" :close-on-press-escape="false" :show-close="!saving">
    <el-tabs v-model="activeName" type="card" @tab-remove="closeItemById">
      <el-tab-pane v-for="item in entries" :key="item.id" :name="item.id" :closable="!item.saving">
        <template #label>
          <span>{{ item.label }}</span>
          <el-tag v-if="item.status === 'loading'" size="small" type="info">加载中</el-tag>
          <el-tag v-else-if="item.status === 'success'" size="small" type="success">已加载</el-tag>
          <el-tag v-else-if="item.status === 'added'" size="small" type="success">已添加</el-tag>
          <el-tag v-else size="small" type="danger">失败</el-tag>
        </template>
        <div v-if="item.status === 'loading'" v-loading="true" class="batch-loading">正在加载订阅信息…</div>
        <el-alert v-else-if="item.status === 'error'" :title="item.error" type="error" show-icon :closable="false"/>
        <div v-else-if="item.ani" class="batch-editor">
          <AniView v-model:ani="item.ani" @callback="confirmItem(item, $event)"/>
        </div>
        <div v-if="item.status === 'error'" class="batch-actions">
          <el-button type="primary" :loading="item.status === 'loading'" @click="retry(item)">重试</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup>
import {computed, ref, watch, onBeforeUnmount} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import * as http from '@/js/http.js'
import AniView from './AniView.vue'
import {batchRequests} from '@/js/batch-addition.js'

const props = defineProps({
  modelValue: Boolean,
  items: {type: Array, default: () => []},
  type: {type: String, required: true}
})
const emit = defineEmits(['update:modelValue', 'done'])
const entries = ref([])
const activeName = ref('')
const visible = computed({get: () => props.modelValue, set: value => emit('update:modelValue', value)})
const saving = computed(() => entries.value.some(item => item.saving))
let generation = 0
const requests = new Map()
const cancel = () => { generation++; requests.forEach(controller => controller.abort()); requests.clear() }
onBeforeUnmount(cancel)

// 单条串行解析，失败只影响当前 tab，后续任务继续执行。
const load = async item => {
  if(item.status === 'added') return
  const current = generation
  const controller = new AbortController()
  requests.get(item.id)?.abort(); requests.set(item.id, controller)
  item.status = 'loading'
  item.error = ''
  try {
    const request = item.request
    const result = (await http.rssToAni(request, {signal:controller.signal, silent:true})).data
    if(current !== generation || controller.signal.aborted || !entries.value.includes(item)) return
    result.match = [...(request.match || result.match || [])]
    result.showDownlaod = false
    item.ani = result
    item.status = 'success'
    item.label = item.ani.title || item.label
  } catch (error) {
    if(current !== generation || controller.signal.aborted || !entries.value.includes(item)) return
    item.status = 'error'
    item.error = error?.message || '订阅加载失败'
  } finally { if(requests.get(item.id) === controller) requests.delete(item.id) }
}

const open = async (items = props.items) => {
  try { await openRequests(batchRequests(items, props.type)) }
  catch(error) { ElMessage.error(error.message); emit('update:modelValue',false) }
}

// 手动或单条添加直接传入 RssToAniDTO，并复用相同的加载与确认流程。
const openRequests = async requests => {
  cancel()
  const current = generation
  entries.value = requests.map((request, index) => ({
    id: `${Date.now()}-${index}`,
    label: request.title || request.subgroup || `订阅 ${index + 1}`,
    request: JSON.parse(JSON.stringify(request)),
    sources: [],
    status: 'loading',
    ani: null,
    error: ''
  }))
  activeName.value = entries.value[0]?.id || ''
  for (const item of [...entries.value]) {
    if(current !== generation) break
    if(entries.value.includes(item)) await load(item)
  }
}

const retry = item => load(item)

const confirmItem = async (item, done) => {
  if(item.saving) { done?.(); return }
  item.saving = true
  try {
    const added = await addSubscription(item)
    if (!added) return
    item.status = 'added'
    ElMessage.success('订阅添加成功')
    closeItem(item)
  } catch (error) {
    ElMessage.error(error?.message || '订阅添加失败')
  } finally {
    item.saving = false
    done?.()
  }
}

// 首次添加禁止覆盖；检测到重复时由用户明确确认后再携带 override 重试。
const addSubscription = async item => {
  try {
    await http.addAni(item.ani)
  } catch (error) {
    const duplicate = error?.code === 'SUBSCRIPTION_EXISTS' || ['此订阅已存在', '订阅标题重复'].includes(error?.message)
    if (!duplicate) throw error
    try {
      await ElMessageBox.confirm(
          `订阅“${item.ani.title}”已存在，是否覆盖原订阅？`,
          '订阅重复',
          {confirmButtonText: '覆盖', cancelButtonText: '取消', type: 'warning'}
      )
    } catch {
      return false
    }
    await http.addAni(item.ani, true)
  }
  return true
}

const closeItem = item => {
  requests.get(item.id)?.abort(); requests.delete(item.id)
  entries.value = entries.value.filter(value => value.id !== item.id)
  if (activeName.value === item.id) activeName.value = entries.value[0]?.id || ''
  if (!entries.value.length) {
    emit('update:modelValue', false)
    emit('done')
  }
}

const closeItemById = id => {
  const item = entries.value.find(value => value.id === id)
  if (item) closeItem(item)
}

watch(() => props.modelValue, value => { if(!value) cancel(); else if(props.items.length) void open(props.items) }, {immediate:true})

defineExpose({open, openRequests})
</script>

<style scoped>
.batch-loading {
  min-height: 180px;
  display: grid;
  place-items: center;
}

.batch-editor {
  width: 100%;
  max-width: 100%;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

/* 限制延迟渲染的备用 RSS 表格，避免固定列宽反向撑大移动端弹窗。 */
.batch-editor :deep(.el-tabs),
.batch-editor :deep(.el-tabs__content),
.batch-editor :deep(.el-tab-pane) {
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.batch-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
