<template>
  <div class="content flex">
    <el-button bg @click="exportConfig" icon="Upload">导出设置</el-button>
    <el-button bg @click="importConfig" icon="Download">导入设置</el-button>
    <UploadView ref="uploadRef" :uploader="http.stageRestore" :extensions="['zip']" :callback="stageCallback"/>
    <el-dialog v-model="previewVisible" title="确认导入设置" width="520px" @closed="stopPolling">
      <el-alert v-if="restoreError" :title="restoreError" type="error" :closable="false"/>
      <template v-else-if="restoreOperation">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="操作 ID">{{ restoreOperation.operationId }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ restoreOperation.status }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ restoreOperation.applicationVersion || '未知' }}</el-descriptions-item>
          <el-descriptions-item label="格式">{{ restoreOperation.legacy ? 'legacy JSON' : 'SQLite fork' }}</el-descriptions-item>
          <el-descriptions-item label="影响范围">{{ restoreOperation.files?.length || 0 }} 项</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="restoreOperation.warnings?.length" class="restore-warning"
                  :title="restoreOperation.warnings.join('；')" type="warning" :closable="false"/>
      </template>
      <template #footer>
        <el-button @click="previewVisible = false">取消</el-button>
        <el-button v-if="canQueryAgain" :loading="querying" @click="queryAgain">
          重新查询
        </el-button>
        <el-button type="primary" :loading="confirming" :disabled="!canConfirm" @click="confirm">
          确认覆盖
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import {ElMessage} from "element-plus";
import {computed, onBeforeUnmount, onMounted, ref} from "vue";
import UploadView from "@/view/custom/UploadView.vue";
import * as http from "@/js/http.js";

let uploadRef = ref()
let previewVisible = ref(false)
let confirming = ref(false)
let restoreError = ref('')
let restoreOperation = ref()
let pollTimer
let pollStartedAt = 0
let pollGeneration = 0
const restoreOperationKey = 'ani-rss.restore-operation'
const terminalStates = new Set(['INVALID', 'SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'MAINTENANCE_REQUIRED'])
const activeStates = new Set(['QUEUED', 'STOPPING', 'SWITCHING'])
const canConfirm = computed(() => restoreOperation.value?.status === 'VALIDATED' && !confirming.value)
const querying = ref(false)
const canQueryAgain = computed(() => Boolean(restoreOperation.value?.operationId)
  && Boolean(restoreError.value || activeStates.has(String(restoreOperation.value?.status || ''))))

let importConfig = () => {
  uploadRef.value?.selectAndUpload()
}

let stageCallback = res => {
  restoreError.value = ''
  if (!res?.operationId || (typeof res.status === 'number' && res.status >= 400)) {
    restoreError.value = res?.message || '备份校验失败'
    ElMessage.error(restoreError.value)
    return
  }
  restoreOperation.value = res.data || res
  previewVisible.value = true
  if (restoreOperation.value.status === 'INVALID') {
    restoreError.value = (restoreOperation.value.errors || []).join('；') || '备份文件无效'
  }
}

const stopPolling = () => {
  pollGeneration++
  clearTimeout(pollTimer)
  pollTimer = undefined
  querying.value = false
}

const finishTerminalStatus = status => {
  stopPolling()
  sessionStorage.removeItem(restoreOperationKey)
  if (status === 'SUCCEEDED') {
    ElMessage.success('导入完成，请重新登录')
  } else if (status === 'INVALID') {
    restoreError.value = (restoreOperation.value?.errors || []).join('；') || '备份文件无效'
  } else if (status === 'ROLLED_BACK') {
    restoreError.value = '导入未完成，系统已回滚'
  } else if (status === 'MAINTENANCE_REQUIRED') {
    restoreError.value = `恢复需要维护处理，操作 ID：${restoreOperation.value?.operationId || '未知'}`
  } else if (status === 'FAILED') {
    restoreError.value = `导入失败，操作 ID：${restoreOperation.value?.operationId || '未知'}`
  }
}

const pollStatus = async (generation = pollGeneration) => {
  const operationId = restoreOperation.value?.operationId
  const status = String(restoreOperation.value?.status || '')
  if (!operationId || generation !== pollGeneration) return
  if (terminalStates.has(status)) {
    finishTerminalStatus(status)
    return
  }
  if (status === 'VALIDATED') return
  if (Date.now() - pollStartedAt >= 120_000) {
    restoreError.value = '状态尚未确认，可重新查询'
    stopPolling()
    return
  }
  try {
    querying.value = true
    const next = await http.restoreStatus(operationId)
    if (generation !== pollGeneration) return
    restoreOperation.value = next
    const nextStatus = String(next?.status || '')
    if (activeStates.has(nextStatus)) {
      pollTimer = setTimeout(() => void pollStatus(generation), 1000)
    } else if (terminalStates.has(nextStatus)) {
      finishTerminalStatus(nextStatus)
    }
  } catch (error) {
    if (generation !== pollGeneration) return
    restoreError.value = error?.message || '暂时无法查询恢复状态'
    if (error?.status === 401) {
      stopPolling()
    } else {
      pollTimer = setTimeout(() => void pollStatus(generation), 1000)
    }
  } finally {
    if (generation === pollGeneration) querying.value = false
  }
}

const queryAgain = () => {
  const operationId = restoreOperation.value?.operationId
  if (!operationId || querying.value) return
  stopPolling()
  restoreError.value = ''
  querying.value = true
  pollStartedAt = Date.now()
  void pollStatus(pollGeneration)
}

const confirm = async () => {
  const operationId = restoreOperation.value?.operationId
  if (!operationId || !canConfirm.value) return
  confirming.value = true
  try {
    restoreOperation.value = await http.confirmRestore(operationId)
    sessionStorage.setItem(restoreOperationKey, operationId)
    pollStartedAt = Date.now()
    const generation = ++pollGeneration
    await pollStatus(generation)
  } catch (error) {
    restoreError.value = error?.message || '恢复任务未能开始'
  } finally {
    confirming.value = false
  }
}

onMounted(() => {
  const operationId = sessionStorage.getItem(restoreOperationKey)
  if (!operationId) return
  restoreOperation.value = {operationId, status: 'CHECKING'}
  restoreError.value = '正在查询上次恢复状态…'
  previewVisible.value = true
  pollStartedAt = Date.now()
  const generation = ++pollGeneration
  void pollStatus(generation)
})

onBeforeUnmount(stopPolling)

let exportConfig = () => {
  let element = document.createElement('a');
  element.href = new URL('api/exportConfig', document.baseURI).toString()

  document.body.appendChild(element);

  element.click();

  document.body.removeChild(element);
}

let props = defineProps(['config'])
</script>
<style scoped>
.content {
  width: 100%;
  justify-content: center;
}

.restore-warning {
  margin-top: 12px;
}
</style>
