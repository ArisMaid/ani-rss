<template>
  <el-dialog v-model="dialogVisible" :before-close="closeDialog" align-center center width="560" title="删除订阅">
    <div v-if="!plan">
      <div v-if="aniList.length === 1">
        <el-text class="mx-1" size="large">是否删除 {{ aniList[0].title }} 第{{ aniList[0].season }}季?</el-text>
      </div>
      <div v-else>
        <el-text class="mx-1" size="large">是否删除共 {{ aniList.length }} 个订阅?</el-text>
      </div>
      <el-checkbox v-model="deleteFiles" class="el-checkbox-danger">
        同时删除下载任务，并将已确认归属的本地文件隔离 7 天
      </el-checkbox>
    </div>
    <div v-else class="plan-preview">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="订阅">{{ plan.subscriptions.length }}</el-descriptions-item>
        <el-descriptions-item label="下载任务">{{ plan.ownershipIds.length }}</el-descriptions-item>
        <el-descriptions-item label="本地文件">{{ plan.files.length }}</el-descriptions-item>
        <el-descriptions-item label="有效期至">{{ formatTime(plan.expiresAt) }}</el-descriptions-item>
      </el-descriptions>
      <el-table v-if="plan.files.length" :data="plan.files" max-height="260" size="small">
        <el-table-column label="文件" min-width="250" prop="relativePath">
          <template #default="scope">
            <el-tooltip :content="scope.row.path" placement="top">
              <span class="file-path">{{ scope.row.relativePath }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="scope">{{ formatSize(scope.row.size) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else :image-size="48" description="没有可隔离的已确认归属文件"/>
    </div>
    <div class="action">
      <el-button v-if="!plan" icon="DocumentChecked" :loading="okLoading" @click="createPlan" text bg type="danger">
        生成删除计划
      </el-button>
      <el-button v-else icon="Delete" :loading="okLoading" @click="executePlan" text bg type="danger">
        执行删除
      </el-button>
      <el-button icon="Close" bg text @click="closeDialog()">取消</el-button>
    </div>
  </el-dialog>
</template>

<script setup>

import {getCurrentInstance, ref} from "vue";
import {
  cancelSubscriptionDeletionPlan,
  createSubscriptionDeletionPlan,
  executeSubscriptionDeletionPlan
} from "@/js/http.js";
import {ElMessage} from "element-plus";

const dialogVisible = ref(false)

const aniList = ref([])

let okLoading = ref(false)
let deleteFiles = ref(false)
const plan = ref(null)

const createPlan = async () => {
  okLoading.value = true
  try {
    const ids = aniList.value.map(it => it.id)
    const response = await createSubscriptionDeletionPlan(ids, deleteFiles.value)
    plan.value = response.data
  } finally {
    okLoading.value = false
  }
}

const executePlan = async () => {
  if (!plan.value) return
  okLoading.value = true
  try {
    const response = await executeSubscriptionDeletionPlan(plan.value.operationId)
    const result = response.data
    plan.value = null
    ElMessage.success(`已删除 ${result.deletedSubscriptions} 个订阅`)
    if (instance.vnode.props.onCallback) {
      emit('callback')
    } else {
      window.$reLoadList()
    }
    dialogVisible.value = false
  } finally {
    okLoading.value = false
  }
}

const closeDialog = async (done) => {
  const operationId = plan.value?.operationId
  plan.value = null
  if (operationId) {
    try {
      await cancelSubscriptionDeletionPlan(operationId)
    } catch {
      // Expired plans are already unusable; closing the dialog remains safe.
    }
  }
  dialogVisible.value = false
  if (typeof done === 'function') done()
}

const formatTime = timestamp => new Date(timestamp).toLocaleString()

const formatSize = bytes => {
  if (!Number.isFinite(bytes) || bytes < 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let value = bytes / 1024
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[index]}`
}

const show = (anis) => {
  if (!anis.length) {
    ElMessage.error('未选择订阅')
    return
  }

  aniList.value = JSON.parse(JSON.stringify(anis))
  deleteFiles.value = false
  plan.value = null
  dialogVisible.value = true
}

defineExpose({
  show
})

const instance = getCurrentInstance()

const emit = defineEmits(['callback'])
</script>

<style scoped>
.action {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 8px;
}

.plan-preview {
  display: grid;
  gap: 12px;
}

.file-path {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 600px) {
  :deep(.el-dialog) {
    width: calc(100vw - 24px) !important;
  }
}
</style>

