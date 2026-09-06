<template>
  <div class="dashboard-page app-page-layout">
    <component v-if="editOpen" :is="EditAniView" ref="editAniRef"/>
    <component v-if="playListOpen" :is="PlayListView" ref="playListRef"/>
    <component v-if="coverOpen" :is="CoverView" ref="coverRef"/>
    <component v-if="delOpen" :is="DelAniView" ref="delAniRef"/>
    <component v-if="rateOpen" :is="BgmRateView" ref="bgmRateRef"/>
    <PageHeaderView title="首页" :subtitle="`${todayLabel} · ${todayText}`">
      <template #actions>
        <el-button :loading="refreshLoading" icon="Refresh" @click="loadAll" class="auto-button">
          刷新
        </el-button>
      </template>
    </PageHeaderView>
    <div class="dashboard-body app-page-content app-page-padding">

      <el-scrollbar class="dashboard-scrollbar">
        <div class="dashboard-content">
          <div class="metric-grid">
            <div class="metric-item">
              <div class="metric-icon subscriptions">
                <el-icon>
                  <List/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">订阅数量</el-text>
                <div class="metric-value">{{ subscriptionTotal }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon enabled">
                <el-icon>
                  <CircleCheck/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">已启用</el-text>
                <div class="metric-value">{{ enabledTotal }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon downloading">
                <el-icon>
                  <Download/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">下载中</el-text>
                <div class="metric-value">{{ downloadingList.length }}</div>
              </div>
            </div>
            <div class="metric-item">
              <div class="metric-icon seeding">
                <el-icon>
                  <Upload/>
                </el-icon>
              </div>
              <div>
                <el-text size="small" type="info">做种中</el-text>
                <div class="metric-value">{{ seedingList.length }}</div>
              </div>
            </div>
          </div>

          <section class="dashboard-section today-section">
            <div class="today-section-content">
              <div class="section-title">
                <h3>今天的订阅</h3>
                <div class="today-heading-actions">
                  <el-tag type="info">{{ todayAnis.length }}</el-tag>
                  <div v-if="todayAnis.length" class="today-scroll-actions">
                    <el-button aria-label="向左滚动" bg circle text @click="scrollToday(-1)">
                      <el-icon>
                        <ArrowLeft/>
                      </el-icon>
                    </el-button>
                    <el-button aria-label="向右滚动" bg circle text @click="scrollToday(1)">
                      <el-icon>
                        <ArrowRight/>
                      </el-icon>
                    </el-button>
                  </div>
                </div>
              </div>
              <el-empty v-if="!todayAnis.length" description="今天没有订阅"/>
              <div v-else ref="todayTrack" class="today-track">
                <div v-for="ani in todayAnis"
                     :key="ani.id"
                     class="today-item">
                  <AniCoverView
                      :item="ani"
                      @edit="item => openDialog('edit', item)"
                      @playlist="item => openDialog('playlist', item)"
                      @cover="item => openDialog('cover', item)"
                      @del="item => openDialog('delete', item)"
                      @rate="item => openDialog('rate', item)"/>
                </div>
              </div>
            </div>
          </section>

          <section class="dashboard-section">
            <div class="section-title">
              <h3>下载中</h3>
              <el-tag type="info">{{ activeTorrents.length }}</el-tag>
            </div>
            <el-empty v-if="!activeTorrents.length" description="当前无下载中任务"/>
            <el-table v-else :data="activeTorrents" class="dashboard-table" size="small">
              <el-table-column label="类型" width="76">
                <template #default="{ row }">
                  <el-tag :type="isDownloading(row) ? 'primary' : 'success'" size="small">
                    {{ isDownloading(row) ? '下载' : '做种' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="名称" min-width="220" prop="name" show-overflow-tooltip/>
              <el-table-column label="进度" width="150">
                <template #default="{ row }">
                  <el-progress :percentage="row.progress || 0" :show-text="false"/>
                </template>
              </el-table-column>
              <el-table-column label="大小" width="110" prop="formatSize"/>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  {{ stateLabel(row.state) }}
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section class="dashboard-section">
            <div class="section-title">
              <h3>疑似停更列表</h3>
              <el-tag type="warning">{{ procrastinatingList.length }}</el-tag>
            </div>
            <el-empty v-if="!procrastinatingList.length" description="暂无疑似停更订阅"/>
            <el-table v-else :data="procrastinatingList" class="dashboard-table" size="small">
              <el-table-column label="订阅" min-width="220" prop="title" show-overflow-tooltip/>
              <el-table-column label="进度" width="100">
                <template #default="{ row }">
                  {{ row.currentEpisodeNumber }} / {{ row.totalEpisodeNumber || '*' }}
                </template>
              </el-table-column>
              <el-table-column label="最后下载" width="150">
                <template #default="{ row }">
                  {{ row.lastDownloadTime > 0 ? fromNow(row.lastDownloadTime) : formatDate(row.releaseDate) }}
                </template>
              </el-table-column>
              <el-table-column label="间隔" width="90">
                <template #default="{ row }">
                  {{ row.procrastinatingDays }} 天
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup>
import {computed, defineAsyncComponent, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref} from "vue";
import {ElMessage} from "element-plus";
import {ArrowLeft, ArrowRight, CircleCheck, Download, List, Upload} from "@element-plus/icons-vue";
import {formatDate, fromNow} from "@/js/format.js";
import * as http from "@/js/http.js";
import PageHeaderView from "@/view/custom/PageHeaderView.vue";

const EditAniView = defineAsyncComponent(() => import('@/view/home/EditAniView.vue'))
const PlayListView = defineAsyncComponent(() => import('@/view/play/PlayListView.vue'))
const CoverView = defineAsyncComponent(() => import('@/view/home/CoverView.vue'))
const DelAniView = defineAsyncComponent(() => import('@/view/home/DelAniView.vue'))
const BgmRateView = defineAsyncComponent(() => import('@/view/home/BgmRateView.vue'))

const weekLabels = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const downloadingStates = ['forcedDL', 'downloading', 'forcedMetaDL', 'metaDL', 'stalledDL', 'checkingDL', 'queuedDL', 'allocating', 'moving']
const seedingStates = ['forcedUP', 'uploading', 'stalledUP', 'queuedUP', 'checkingUP']
const dayMs = 24 * 60 * 60 * 1000

const refreshLoading = ref(false)
const weekList = ref([])
const subscriptionTotalValue = ref(0)
const torrentsInfos = ref([])
const todayTrack = ref()
const editAniRef = ref()
const delAniRef = ref()
const coverRef = ref()
const playListRef = ref()
const bgmRateRef = ref()
const editOpen = ref(false)
const playListOpen = ref(false)
const coverOpen = ref(false)
const delOpen = ref(false)
const rateOpen = ref(false)
const config = ref({
  procrastinatingDay: 14
})

let timer
let pollingActive = false
let pollGeneration = 0
let torrentsRequest
let torrentsController

const todayLabel = computed(() => weekLabels[new Date().getDay()])
const flatAnis = computed(() => weekList.value.flatMap(week => week.items || []))
const enabledAnis = computed(() => flatAnis.value.filter(item => item.enable))
const subscriptionTotal = computed(() => subscriptionTotalValue.value || flatAnis.value.length)
const enabledTotal = computed(() => enabledAnis.value.length)
const todayAnis = computed(() => {
  const today = weekList.value.find(week => week.weekLabel === todayLabel.value)
  return today ? (today.items || []).filter(item => item.enable) : []
})
const todayText = computed(() => todayAnis.value.length ? `${todayAnis.value.length} 个订阅` : '没有订阅')
const downloadingList = computed(() => torrentsInfos.value.filter(isDownloading))
const seedingList = computed(() => torrentsInfos.value.filter(isSeeding))
const activeTorrents = computed(() => torrentsInfos.value.filter(item => item.state !== 'stoppedUP'))
const procrastinatingList = computed(() => {
  const threshold = Number(config.value.procrastinatingDay || 14)
  return enabledAnis.value
      .filter(item => item.procrastinating !== false)
      .filter(item => !item.totalEpisodeNumber || item.currentEpisodeNumber < item.totalEpisodeNumber)
      .map(item => {
        const time = getCompareTime(item)
        const procrastinatingDays = time ? Math.floor((Date.now() - time) / dayMs) : 0
        return {
          ...item,
          procrastinatingDays
        }
      })
      .filter(item => item.procrastinatingDays >= threshold)
      .sort((a, b) => b.procrastinatingDays - a.procrastinatingDays)
})

const getCompareTime = item => {
  if (item.lastDownloadTime > 0) {
    return item.lastDownloadTime
  }
  const releaseTime = new Date(item.releaseDate).getTime()
  return Number.isNaN(releaseTime) ? 0 : releaseTime
}

const isDownloading = item => downloadingStates.includes(item.state)
const isSeeding = item => seedingStates.includes(item.state)

const stateLabel = state => {
  const map = {
    forcedDL: '强制下载',
    downloading: '下载中',
    forcedMetaDL: '获取元数据',
    metaDL: '元数据',
    stalledDL: '下载停滞',
    queuedDL: '等待下载',
    checkingDL: '检查下载',
    allocating: '分配空间',
    moving: '移动中',
    forcedUP: '强制上传',
    uploading: '上传中',
    stalledUP: '做种中',
    queuedUP: '等待做种',
    checkingUP: '检查做种'
  }
  return map[state] || state || '未知'
}

const scrollToday = direction => {
  const track = todayTrack.value
  if (!track) {
    return
  }
  track.scrollBy({
    left: direction * Math.min(track.clientWidth * 0.75, 560),
    behavior: 'smooth'
  })
}

const loadAni = async () => {
  const res = await http.listAni()
  weekList.value = res.data.weekList || []
  subscriptionTotalValue.value = res.data.total || 0
}

const loadConfig = async () => {
  const res = await http.config()
  config.value = res.data || config.value
}

const loadTorrents = () => {
  if (torrentsRequest) return torrentsRequest
  const generation = pollGeneration
  const controller = new AbortController()
  torrentsController = controller
  const request = http.torrentsInfos({signal: controller.signal})
      .then(res => {
        if (generation === pollGeneration) {
          torrentsInfos.value = res.data || []
        }
        return res
      })
      .finally(() => {
        if (torrentsRequest === request) {
          torrentsRequest = undefined
          torrentsController = undefined
        }
      })
  torrentsRequest = request
  return request
}

const loadAll = async () => {
  refreshLoading.value = true
  try {
    await Promise.all([
      loadAni(),
      loadConfig(),
      loadTorrents()
    ])
  } catch (error) {
    if (error?.code !== 'REQUEST_ABORTED') {
      ElMessage.error(error?.message || '首页数据加载失败')
    }
  } finally {
    refreshLoading.value = false
  }
}

const schedulePolling = () => {
  if (!pollingActive || document.hidden || timer) return
  timer = setTimeout(async () => {
    timer = undefined
    if (!pollingActive || document.hidden) return
    try {
      await loadTorrents()
    } catch {
      // Background polling is deliberately quiet; manual refresh still reports errors.
    } finally {
      schedulePolling()
    }
  }, 5000)
}

const startPolling = () => {
  pollingActive = true
  pollGeneration++
  void loadTorrents().catch(() => {}).finally(schedulePolling)
}

const stopPolling = () => {
  pollingActive = false
  pollGeneration++
  clearTimeout(timer)
  timer = undefined
  torrentsController?.abort()
}

const handleVisibilityChange = () => {
  if (document.hidden) {
    pollGeneration++
    clearTimeout(timer)
    timer = undefined
    torrentsController?.abort()
  } else if (pollingActive) {
    pollGeneration++
    void loadTorrents().catch(() => {}).finally(schedulePolling)
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  void loadAll()
})

const openDialog = async (name, payload) => {
  const flags = {
    edit: editOpen,
    playlist: playListOpen,
    cover: coverOpen,
    delete: delOpen,
    rate: rateOpen
  }
  const refs = {
    edit: editAniRef,
    playlist: playListRef,
    cover: coverRef,
    delete: delAniRef,
    rate: bgmRateRef
  }
  flags[name].value = true
  await nextTick()
  const startedAt = Date.now()
  const showWhenReady = () => {
    const show = refs[name].value?.show
    if (show) {
      show(payload)
      return
    }
    if (Date.now() - startedAt < 5_000) {
      setTimeout(showWhenReady, 16)
    }
  }
  showWhenReady()
}
onActivated(startPolling)
onDeactivated(stopPolling)
onUnmounted(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<style scoped>
.metric-grid {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.metric-item {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.metric-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.metric-icon.subscriptions {
  color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.metric-icon.enabled {
  color: var(--el-color-success);
  background-color: var(--el-color-success-light-9);
}

.metric-icon.downloading {
  color: var(--el-color-warning);
  background-color: var(--el-color-warning-light-9);
}

.metric-icon.seeding {
  color: var(--el-color-info);
  background-color: var(--el-color-info-light-9);
}

.metric-value {
  margin-top: 2px;
  font-size: 24px;
  line-height: 1.2;
  font-weight: 700;
}

.dashboard-scrollbar {
  flex: 1;
  min-height: 0;
}

.dashboard-content {
  display: grid;
  grid-template-columns: minmax(280px, 0.9fr) minmax(320px, 1.1fr);
  gap: 14px;
  padding-bottom: 8px;
}

.dashboard-section {
  min-width: 0;
  padding: 12px;
  border-radius: 8px;
  background-color: var(--el-bg-color);
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.section-title h3 {
  font-size: 16px;
  line-height: 1.4;
}

.today-section {
  position: relative;
  grid-column: 1 / -1;
  overflow: hidden;
}

.today-section-content {
  min-width: 0;
}

.today-heading-actions,
.today-scroll-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.today-scroll-actions .el-button {
  width: 28px;
  height: 28px;
  margin-left: 0;
}

.today-track {
  min-width: 0;
  display: flex;
  gap: 10px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 2px 2px 8px;
  scroll-behavior: smooth;
  scroll-snap-type: x proximity;
  scrollbar-color: var(--el-border-color) transparent;
  scrollbar-width: thin;
}

.today-track::-webkit-scrollbar {
  height: 6px;
}

.today-track::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background-color: var(--el-border-color);
}

.today-track::-webkit-scrollbar-track {
  background-color: transparent;
}

.today-item {
  flex: 0 0 142px;
  min-width: 0;
  scroll-snap-align: start;
}

.dashboard-table {
  width: 100%;
}

@media (max-width: 900px) {
  .metric-grid,
  .dashboard-content {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 560px) {
  .metric-grid,
  .dashboard-content {
    grid-template-columns: 1fr;
  }

  .metric-item {
    padding: 10px;
  }

  .today-scroll-actions {
    display: none;
  }

  .today-item {
    flex-basis: 118px;
  }
}
</style>
