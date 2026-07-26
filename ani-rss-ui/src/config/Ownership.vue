<template>
  <div class="ownership-management" v-loading="loading">
    <div class="toolbar">
      <el-button :loading="loading" bg icon="Refresh" text @click="refresh">刷新</el-button>
      <el-popconfirm title="立即清理所有已到期隔离文件？" @confirm="purgeExpired">
        <template #reference>
          <el-button bg icon="Delete" text type="danger">清理到期文件</el-button>
        </template>
      </el-popconfirm>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="`待确认归属 (${candidates.length})`" name="candidates">
        <el-table :data="candidates" height="390" size="small">
          <el-table-column label="任务" min-width="180">
            <template #default="scope">
              <div class="primary-text">{{ scope.row.taskName || scope.row.remoteTaskId }}</div>
              <el-text size="small" truncated>{{ scope.row.infoHash || '-' }}</el-text>
            </template>
          </el-table-column>
          <el-table-column label="保存位置" min-width="190" prop="savePath" show-overflow-tooltip/>
          <el-table-column label="订阅" min-width="190">
            <template #default="scope">
              <el-select v-model="candidateSelections[candidateKey(scope.row)]" filterable size="small">
                <el-option v-for="subscription in subscriptions" :key="subscription.id"
                           :label="subscriptionLabel(subscription)" :value="subscription.id"/>
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="判定" width="120">
            <template #default="scope">
              <el-tag :type="scope.row.autoAdoptable ? 'success' : 'warning'" size="small">
                {{ scope.row.autoAdoptable ? '可自动接管' : '需人工确认' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="92">
            <template #default="scope">
              <el-button :loading="busy === candidateKey(scope.row)" icon="Link" text type="primary"
                         @click="adopt(scope.row)">接管</el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty :image-size="56" description="没有待确认任务"/></template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`已登记 (${ownerships.length})`" name="ownerships">
        <el-table :data="ownerships" height="390" size="small">
          <el-table-column label="订阅" min-width="160">
            <template #default="scope">{{ subscriptionName(scope.row.subscriptionId) }}</template>
          </el-table-column>
          <el-table-column label="下载器" width="120" prop="downloaderType"/>
          <el-table-column label="远端 ID" min-width="150" prop="remoteTaskId" show-overflow-tooltip/>
          <el-table-column label="保存根目录" min-width="210" prop="saveRoot" show-overflow-tooltip/>
          <el-table-column label="状态" width="120">
            <template #default="scope"><el-tag size="small">{{ scope.row.state }}</el-tag></template>
          </el-table-column>
          <template #empty><el-empty :image-size="56" description="没有归属记录"/></template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`隔离区 (${quarantineOperations.length})`" name="quarantine">
        <el-table :data="quarantineOperations" height="390" size="small">
          <el-table-column type="expand">
            <template #default="scope">
              <ul class="file-list">
                <li v-for="entry in scope.row.entries" :key="entry.entryId">
                  <el-text truncated>{{ entry.originalPath }}</el-text>
                </li>
              </ul>
            </template>
          </el-table-column>
          <el-table-column label="操作 ID" min-width="160" prop="operationId" show-overflow-tooltip/>
          <el-table-column label="文件" width="80" prop="fileCount"/>
          <el-table-column label="状态" width="110" prop="state"/>
          <el-table-column label="清理时间" min-width="165">
            <template #default="scope">{{ formatTime(scope.row.purgeAfter) }}</template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="170">
            <template #default="scope">
              <el-button :disabled="scope.row.state !== 'QUARANTINED'" icon="RefreshLeft" text
                         type="primary" @click="restore(scope.row)">恢复</el-button>
              <el-button :disabled="scope.row.state !== 'QUARANTINED'" icon="Delete" text
                         type="danger" @click="purgeNow(scope.row)">清理</el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty :image-size="56" description="隔离区为空"/></template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import * as http from '@/js/http.js'

const activeTab = ref('candidates')
const loading = ref(false)
const busy = ref('')
const candidates = ref([])
const ownerships = ref([])
const quarantine = ref([])
const subscriptions = ref([])
const candidateSelections = ref({})

const quarantineOperations = computed(() => {
  const operations = new Map()
  for (const entry of quarantine.value) {
    let operation = operations.get(entry.operationId)
    if (!operation) {
      operation = {
        operationId: entry.operationId,
        entries: [],
        fileCount: 0,
        purgeAfter: entry.purgeAfter,
        states: new Set()
      }
      operations.set(entry.operationId, operation)
    }
    operation.entries.push(entry)
    operation.fileCount += 1
    operation.purgeAfter = Math.max(operation.purgeAfter, entry.purgeAfter)
    operation.states.add(entry.state)
  }
  return [...operations.values()].map(operation => ({
    ...operation,
    state: operation.states.size === 1 ? [...operation.states][0] : 'MIXED'
  }))
})

const refresh = async () => {
  loading.value = true
  try {
    const [candidateResponse, ownershipResponse, quarantineResponse, subscriptionResponse] = await Promise.all([
      http.listOwnershipCandidates(),
      http.listOwnerships(),
      http.listQuarantine(),
      http.listSubscriptionsV2()
    ])
    candidates.value = candidateResponse.data
    ownerships.value = ownershipResponse.data
    quarantine.value = quarantineResponse.data
    subscriptions.value = subscriptionResponse.data
    const selections = {...candidateSelections.value}
    for (const candidate of candidates.value) {
      selections[candidateKey(candidate)] ||= candidate.subscriptionId || ''
    }
    candidateSelections.value = selections
  } finally {
    loading.value = false
  }
}

const adopt = async candidate => {
  const key = candidateKey(candidate)
  const subscriptionId = candidateSelections.value[key]
  if (!subscriptionId) {
    ElMessage.error('请选择归属订阅')
    return
  }
  await ElMessageBox.confirm('确认由 ANI-RSS 接管此下载任务？', '人工接管', {
    confirmButtonText: '确认接管',
    cancelButtonText: '取消',
    type: 'warning'
  })
  busy.value = key
  try {
    await http.adoptOwnership({
      remoteTaskId: candidate.remoteTaskId,
      infoHash: candidate.infoHash,
      subscriptionId,
      confirmed: true
    })
    ElMessage.success('任务归属已登记')
    await refresh()
  } finally {
    busy.value = ''
  }
}

const restore = async operation => {
  await ElMessageBox.confirm(`恢复 ${operation.fileCount} 个隔离文件？`, '恢复隔离文件', {
    confirmButtonText: '恢复', cancelButtonText: '取消', type: 'warning'
  })
  await http.restoreQuarantine(operation.operationId)
  ElMessage.success('隔离文件已恢复')
  await refresh()
}

const purgeNow = async operation => {
  await ElMessageBox.confirm(`立即永久清理 ${operation.fileCount} 个隔离文件？`, '永久清理', {
    confirmButtonText: '永久清理', cancelButtonText: '取消', type: 'error'
  })
  await http.purgeQuarantine(operation.operationId)
  ElMessage.success('隔离文件已清理')
  await refresh()
}

const purgeExpired = async () => {
  const response = await http.purgeExpiredQuarantine()
  ElMessage.success(`已清理 ${response.data.purged} 个到期文件`)
  await refresh()
}

const candidateKey = candidate => `${candidate.downloaderType}:${candidate.remoteTaskId || candidate.infoHash}`
const subscriptionLabel = subscription => `${subscription.title} 第${subscription.season}季`
const subscriptionName = id => {
  const subscription = subscriptions.value.find(item => item.id === id)
  return subscription ? subscriptionLabel(subscription) : id
}
const formatTime = timestamp => new Date(timestamp).toLocaleString()

onMounted(refresh)
</script>

<style scoped>
.ownership-management {
  min-height: 450px;
}

.toolbar {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.primary-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 8px 24px;
}
</style>
