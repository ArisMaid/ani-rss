<template>
  <div class="subscription-page app-page-layout">
    <component v-if="dialogs.add" :is="AddView" ref="addRef"/>
    <component v-if="dialogs.collection" :is="CollectionView" ref="collectionRef"/>
    <component v-if="dialogs.manage" :is="ManageView" ref="manageRef"/>
    <PageHeaderView title="订阅" :subtitle="`共 ${subscriptionTotal} 个订阅`"/>
    <div class="subscription-body app-page-content app-page-padding">
      <div class="subscription-toolbar">
        <div class="subscription-filters">
          <el-input
              v-model="title"
              class="subscription-search"
              clearable
              placeholder="搜索"
              prefix-icon="Search"
              @clear="changeFilterList"
              @input="changeFilterList"/>
          <el-select
              v-model:model-value="releaseDate"
              class="subscription-select"
              clearable
              placeholder="日期"
              @change="selectChange">
            <el-option v-for="it in releaseDateList"
                       :key="it"
                       :label="it"
                       :value="it"/>
          </el-select>
          <el-select
              v-model:model-value="enable"
              class="subscription-select"
              @change="selectChange">
            <el-option v-for="selectItem in enableSelect"
                       :key="selectItem.label"
                       :label="selectItem.label"
                       :value="selectItem.label"/>
          </el-select>
        </div>
        <div class="subscription-actions">
          <el-dropdown trigger="click">
            <el-button aria-label="添加" type="primary" class="auto-button" icon="Plus">
              添加
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openDialog('add')">
                  添加订阅
                </el-dropdown-item>
                <el-dropdown-item @click="openDialog('collection')">
                  添加合集
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <PopconfirmView title="立即刷新全部订阅?" @confirm="refreshAni">
            <template #reference>
              <el-button aria-label="刷新" :loading="refreshLoading" :disabled="refreshLoading"
                         class="auto-button" icon="Refresh">
                刷新
              </el-button>
            </template>
          </PopconfirmView>
          <el-button aria-label="管理" @click="openDialog('manage')" class="auto-button" icon="Fold">
            管理
          </el-button>
        </div>
      </div>
      <SubscriptionListView
          ref="listRef"
          :filter="filter"
          :title="title"
          :view-mode="subscriptionViewMode"
          @loaded="listLoaded"/>
    </div>
  </div>
</template>

<script setup>
import {defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref} from "vue";
import {ElMessage} from "element-plus";
import {useLocalStorage} from "@vueuse/core";
import SubscriptionListView from "@/view/home/SubscriptionListView.vue";
import PopconfirmView from "@/view/custom/PopconfirmView.vue";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";
import {subscriptionViewMode} from "@/js/global.js";
import * as http from "@/js/http.js";

const listRef = ref()
const addRef = ref()
const collectionRef = ref()
const manageRef = ref()
const title = ref('')
const releaseDate = ref('')
const releaseDateList = ref([])
const subscriptionTotal = ref(0)
const refreshLoading = ref(false)
const enable = useLocalStorage('select-enable', '已启用')
const enableSelect = [
  {
    label: '全部',
    fun: () => true
  },
  {
    label: '已启用',
    fun: item => item.enable
  },
  {
    label: '未启用',
    fun: item => !item.enable
  }
]
const filter = ref(() => true)
const AddView = defineAsyncComponent(() => import("@/view/home/AddView.vue"))
const CollectionView = defineAsyncComponent(() => import("@/view/home/CollectionView.vue"))
const ManageView = defineAsyncComponent(() => import("@/view/home/ManageView.vue"))
const dialogs = reactive({add: false, collection: false, manage: false})

const dialogRefs = {add: addRef, collection: collectionRef, manage: manageRef}

const openDialog = name => {
  dialogs[name] = true
  const startedAt = Date.now()
  const showWhenReady = () => {
    const show = dialogRefs[name]?.value?.show
    if (show) {
      show()
      return
    }
    if (Date.now() - startedAt < 5_000) {
      setTimeout(showWhenReady, 16)
    }
  }
  void nextTick(showWhenReady)
}

const changeFilterList = () => {
  listRef.value?.changeFilterList(title.value)
}

const selectChange = () => {
  filter.value = it => {
    const selectedEnable = enableSelect.find(item => item.label === enable.value)
    if (selectedEnable && !selectedEnable.fun(it)) {
      return false
    }
    if (!releaseDate.value) {
      return true
    }

    return releaseDate.value === it.releaseDate.replace(/-\d{2}$/, '')
  }
  changeFilterList()
}

const listLoaded = data => {
  releaseDateList.value = data.releaseDateList || []
  subscriptionTotal.value = data.total || 0
  if (data?.refresh?.running && !refreshLoading.value) {
    startRefreshTracking()
  }
}

let refreshTimer
let refreshGeneration = 0
const refreshStartedAt = ref(0)
const MAX_REFRESH_WAIT = 120_000

const stopRefreshPolling = () => {
  refreshGeneration++
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = undefined
  }
}

const finishRefresh = (refresh, timedOut = false) => {
  refreshLoading.value = false
  if (timedOut) {
    ElMessage.warning('刷新仍在后台运行，请稍后查看列表状态')
  } else if (refresh?.failedCount > 0) {
    ElMessage.warning(`刷新完成，但有 ${refresh.failedCount} 个订阅失败`)
  }
}

const startRefreshTracking = () => {
  if (refreshLoading.value) return
  stopRefreshPolling()
  refreshLoading.value = true
  refreshStartedAt.value = Date.now()
  const generation = refreshGeneration
  refreshTimer = setTimeout(() => void pollRefresh(generation), 2000)
}

const pollRefresh = async generation => {
  if (!refreshLoading.value || generation !== refreshGeneration) return
  if (Date.now() - refreshStartedAt.value >= MAX_REFRESH_WAIT) {
    finishRefresh(null, true)
    return
  }
  const data = await listRef.value?.getList()
  if (generation !== refreshGeneration) return
  const refresh = data?.refresh
  if (refresh && !refresh.running) {
    finishRefresh(refresh)
    return
  }
  refreshTimer = setTimeout(() => void pollRefresh(generation), 2000)
}

const refreshAni = async () => {
  if (refreshLoading.value) return
  stopRefreshPolling()
  const generation = refreshGeneration
  refreshLoading.value = true
  refreshStartedAt.value = Date.now()
  try {
    const res = await http.refreshAll()
    ElMessage.success(res.message)
    await pollRefresh(generation)
  } catch {
    if (generation === refreshGeneration) refreshLoading.value = false
  }
}

onMounted(() => {
  selectChange()
})

onBeforeUnmount(stopRefreshPolling)
</script>

<style scoped>
.subscription-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 10px;
}

.subscription-filters,
.subscription-actions {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.subscription-filters {
  flex: 1;
}

.subscription-actions {
  flex-shrink: 0;
}

.subscription-actions > * {
  margin: 0 !important;
}

.subscription-actions :deep(.el-button) {
  margin: 0;
}

.subscription-search {
  width: 220px;
}

.subscription-select {
  width: 128px;
}

@media (max-width: 900px) {
  .subscription-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .subscription-filters,
  .subscription-actions {
    width: 100%;
  }

  .subscription-filters {
    flex-wrap: wrap;
  }

  .subscription-actions {
    justify-content: flex-end;
  }

  .subscription-search {
    flex: 1 1 180px;
  }

  .subscription-select {
    flex: 1 1 120px;
  }
}

@media (max-width: 560px) {
  .subscription-toolbar {
    padding-bottom: 8px;
  }

  .subscription-actions {
    justify-content: flex-end;
    gap: 4px;
  }
}
</style>
