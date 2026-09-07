<template>
  <el-dialog v-model="batchAdditionDialogVisible" align-center center title="正在批量添加订阅"
             width="500"
             :close-on-click-modal="false"
             :close-on-press-escape="false"
             :show-close="false">
    <div>
      <el-progress :percentage="Number.parseInt((batchAdditionNum / rssList.length) * 100.0)"/>
    </div>
    <div>
      {{ batchAdditionNum }} / {{ rssList.length }}
    </div>
  </el-dialog>
  <el-dialog v-model="matchDialogVisible" align-center center title="匹配" width="auto">
    <div class="match-content">
      <el-radio-group v-model="addAni.match">
        <div v-for="regexItems in regexList" class="match-item">
          <el-radio :label="JSON.stringify(regexItems)"
                    :value="JSON.stringify(regexItems.map(it => it.regex))">
            <el-tag v-if="regexItems.length" v-for="regexItem in regexItems" class="tag-margin">
              {{ regexItem.label }}
            </el-tag>
            <el-tag v-else type="success">全部</el-tag>
          </el-radio>
        </div>
      </el-radio-group>
    </div>
    <div class="dialog-footer">
      <el-button icon="Check" @click="async ()=>{
          emit('callback', addAni)
          dialogVisible = false
          matchDialogVisible = false
      }" text bg>确定
      </el-button>
    </div>
  </el-dialog>
  <el-dialog v-model="dialogVisible" center title="AnimeGarden" @closed="close">
    <el-checkbox-group v-model="rssList">
      <div class="content-wrapper">
        <div class="search-section">
          <div class="flex season-selector">
            <el-button :disabled="rssList.length < 1" bg icon="Plus" text @click="batchAddition">批量添加</el-button>
          </div>
        </div>
        <div v-loading="loading" :data-loading="loading" class="scroll-container">
          <el-tabs v-model="activeName" class="week-tabs">
            <el-tab-pane v-for="item in data.items" :key="item.weekLabel"
                         :label="item.weekLabel" :name="item.weekLabel" lazy>
              <el-scrollbar class="week-pane-scrollbar">
                <div class="collapse-content">
                  <el-collapse accordion @change="collapseChange">
                    <el-collapse-item v-for="anime in item.subjects" :name="anime.id">
                      <template #title>
                        <div class="flex collapse-title">
                          <SafeImageView v-if="anime.cover" :src-url="anime.cover" :lazy="true" class="cover"
                                         @click.stop="open(`https://animes.garden/subject/${anime.id}`)"/>
                          <div class="flex collapse-title">
                            <el-text :truncated="false" line-clamp="1" size="small"
                                     class="title-text">
                              {{ anime.name }}
                            </el-text>
                          </div>
                          <div v-if="anime['score'] > 0" class="score-margin">
                            <h4 class="score-color">
                              {{ anime['score'].toFixed(1) }}
                            </h4>
                          </div>
                          <el-badge v-if="anime['exists']" class="item badge-margin" type="primary"
                                    value="已订阅"/>
                        </div>
                      </template>
                      <div v-if="selectName === anime.id" v-loading="groupLoading"
                           class="group-content">
                        <el-collapse accordion>
                          <el-collapse-item v-for="group in groups[anime.id]">
                            <template #title>
                              <div class="group-title-wrapper">
                                <div class="group-checkbox-wrapper">
                                  <el-checkbox :value="JSON.stringify(group)" class="checkbox-margin" @click.stop/>
                                </div>
                                <div class="group-label">
                                  <el-text style="max-width: 100px;" truncated>{{ group.name }}</el-text>
                                  &nbsp;
                                  <el-text class="mx-1" size="small">
                                    {{ fromNow(group['lastUpdatedAt'], 'MM/DD/YYYY') }}
                                  </el-text>
                                </div>
                                <div v-if="showTag()">
                                  <el-tag v-for="tag in group['groupRegex']['tags']"
                                          class="tag-margin">
                                    {{ tag }}
                                  </el-tag>
                                </div>
                                <div class="group-action">
                                  <el-button bg @click.stop="callback(group)" icon="Plus">
                                    添加
                                  </el-button>
                                </div>
                              </div>
                            </template>
                            <div class="group-items">
                              <div v-for="ti in group.items" class="item-margin">
                                <el-card shadow="never">
                                  <div>
                                    <h5>
                                      {{ ti.title }}
                                    </h5>
                                    <div class="item-footer">
                                      <p>
                                        {{ ti['formatSize'] }}
                                        {{ ti['createdAt'] }}
                                      </p>
                                      <div>
                                        <el-button :icon="DocumentCopy" bg text @click="copy(ti['magnet']  )"/>
                                      </div>
                                    </div>
                                  </div>
                                </el-card>
                              </div>
                            </div>
                          </el-collapse-item>
                        </el-collapse>
                      </div>
                    </el-collapse-item>
                  </el-collapse>
                </div>
              </el-scrollbar>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </el-checkbox-group>
  </el-dialog>
</template>

<script setup>
import {onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch} from "vue";
import {ElMessage, ElText} from "element-plus";
import {DocumentCopy} from "@element-plus/icons-vue";
import * as http from "@/js/http.js";
import SafeImageView from "@/view/custom/SafeImageView.vue";
import {fromNow} from "@/js/format.js";
import {enrichSubjects} from "@/js/mikan-loader.js";

// 批量添加订阅
let rssList = ref([]);

let groupLoading = ref(false)
let activeName = ref("")
let dialogVisible = ref(false)
let loading = ref(false)
let listGeneration = 0
let listController
let enrichmentController
let groupController
let groupGeneration = 0
let enrichmentKey = ''
let currentListValid = false
let needsListReload = false
let lastListBgmUrl = ''
let data = ref({
  'items': []
})

let show = (bgmUrl = '') => {
  closeRequests()
  listGeneration++
  dialogVisible.value = true
  data.value = {
    'items': []
  }
  rssList.value = []
  list(bgmUrl)
}

let list = async (bgmUrl = '') => {
  lastListBgmUrl = bgmUrl || ''
  const generation = listGeneration
  const controller = new AbortController()
  listController = controller
  currentListValid = false
  needsListReload = true
  selectName.value = ''
  groups.value = {}
  loading.value = true
  return http.animeGardenList(lastListBgmUrl, {signal: controller.signal})
      .then(res => {
        if (generation !== listGeneration || controller.signal.aborted) return
        const items = res?.data
        if (!Array.isArray(items)) {
          throw new Error('AnimeGarden 列表响应格式无效')
        }

        if (!items || items.length < 1) {
          ElMessage.warning("搜索结果为空")
        }

        data.value.items = items
        currentListValid = true
        needsListReload = false
        if (items.length) {
          activeName.value = items[0].weekLabel
        }
        void startEnrichment(generation)
      })
      .catch(error => {
        if (error?.code !== 'REQUEST_ABORTED' && generation === listGeneration) {
          ElMessage.error(error?.message || 'AnimeGarden 列表加载失败')
        }
      })
      .finally(() => {
        if (generation === listGeneration) loading.value = false
        if (listController === controller) listController = undefined
      });
}

const updateSubjects = subjects => {
  for (const week of data.value.items || []) {
    for (const subject of week.subjects || []) {
      const enrichment = subjects?.[String(subject.id)]
      if (!enrichment) continue
      if (enrichment.cover) subject.cover = enrichment.cover
      const rawScore = enrichment.score
      const hasUsableRawScore = (typeof rawScore === 'number' && !Number.isNaN(rawScore))
        || (typeof rawScore === 'string' && rawScore.trim() !== '')
      const score = hasUsableRawScore ? Number(rawScore) : Number.NaN
      if (Number.isFinite(score)) subject.score = score
    }
  }
}

const startEnrichment = async (generation = listGeneration) => {
  if (document.hidden || !dialogVisible.value || generation !== listGeneration) return
  const week = data.value.items?.find(item => item.weekLabel === activeName.value)
  const ids = (week?.subjects || []).map(subject => String(subject.id)).filter(Boolean)
  if (!ids.length) return
  const key = `${generation}:${activeName.value}`
  if (enrichmentKey === key) return
  enrichmentKey = key
  enrichmentController?.abort()
  const controller = new AbortController()
  enrichmentController = controller
  try {
    await enrichSubjects({
      ids,
      fetchSubjects: (subjectIds, options) => http.animeGardenEnrichment(subjectIds, options),
      onUpdate: payload => {
        if (generation !== listGeneration || controller.signal.aborted) return
        updateSubjects(payload.subjects)
      },
      signal: controller.signal
    })
  } catch (error) {
    if (error?.code !== 'REQUEST_ABORTED' && error?.name !== 'AbortError'
        && generation === listGeneration && !controller.signal.aborted) {
      ElMessage.warning('封面或评分暂时不可用，可重新切换星期重试')
    }
  } finally {
    if (enrichmentController === controller) enrichmentController = undefined
  }
}

const closeRequests = () => {
  listController?.abort()
  enrichmentController?.abort()
  groupController?.abort()
  groupGeneration++
  listController = undefined
  enrichmentController = undefined
  groupController = undefined
  enrichmentKey = ''
  groupLoading.value = false
  loading.value = false
}

const close = () => {
  listGeneration++
  currentListValid = false
  needsListReload = false
  closeRequests()
}

watch(activeName, () => startEnrichment())

const pauseForLifecycle = () => {
  const listWasInFlight = Boolean(listController)
  listGeneration++
  if (listWasInFlight) {
    currentListValid = false
    needsListReload = true
  }
  closeRequests()
}

const resumeFromLifecycle = () => {
  if (!dialogVisible.value || document.hidden) return
  if (needsListReload || !currentListValid) {
    void list(lastListBgmUrl)
    return
  }
  void startEnrichment()
  if (selectName.value && !groups.value[selectName.value]) {
    collapseChange(selectName.value)
  }
}

const handleVisibilityChange = () => {
  if (document.hidden) pauseForLifecycle()
  else resumeFromLifecycle()
}

onMounted(() => document.addEventListener('visibilitychange', handleVisibilityChange))
onActivated(() => resumeFromLifecycle())
onDeactivated(() => pauseForLifecycle())
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  close()
})

let selectName = ref('')
let groups = ref({})

let collapseChange = (v) => {
  if (!v) {
    groupGeneration++
    groupController?.abort()
    groupController = undefined
    groupLoading.value = false
    selectName.value = ''
    return
  }
  if (document.hidden) return
  selectName.value = v
  if (groups.value[v]) {
    return;
  }
  groupController?.abort()
  const generation = ++groupGeneration
  const controller = new AbortController()
  groupController = controller
  groupLoading.value = true
  http.animeGardenGroup(v, {signal: controller.signal})
      .then(res => {
        if (generation !== groupGeneration || controller.signal.aborted) return
        groups.value[v] = res.data
      })
      .catch(error => {
        if (error?.code !== 'REQUEST_ABORTED' && error?.name !== 'AbortError'
            && generation === groupGeneration && !controller.signal.aborted) {
          ElMessage.warning('字幕组资源暂时不可用，请重新展开重试')
        }
      })
      .finally(() => {
        if (generation === groupGeneration) groupLoading.value = false
        if (groupController === controller) groupController = undefined
      })
}


let matchDialogVisible = ref(false)

let addAni = ref({
  'bgmUrl': '',
  'url': '',
  'match': '',
  'group': ''
})

let regexList = ref([])

let callback = v => {
  let {bgmId, rss, name} = v
  regexList.value = JSON.parse(JSON.stringify(v.groupRegex.regexList))

  addAni.value.bgmUrl = `https://bgm.tv/subject/${bgmId}`
  addAni.value.url = rss
  addAni.value.subgroup = name
  addAni.value.match = '[]'

  regexList.value.push([])
  matchDialogVisible.value = true
}


let showTag = () => {
  return window.innerWidth > 900;
}

let open = url => {
  window.open(url);
}

defineExpose({show, collapseChange})

let emit = defineEmits(['callback'])


let batchAdditionNum = ref(0)
let batchAdditionDialogVisible = ref(false)

let batchAddition = async () => {
  batchAdditionNum.value = 0
  batchAdditionDialogVisible.value = true

  try {
    ElMessage.success("添加中....")
    let map = rssList.value.reduce((acc, item) => {
      let parsedItem = JSON.parse(item);
      let bgmId = parsedItem['bgmId'];
      if (!acc[bgmId]) {
        acc[bgmId] = [];
      }
      acc[bgmId].push(parsedItem);
      return acc;
    }, {})
    for (let item of Object.values(map)) {
      let ani = {
        "url": item[0]['rss'],
        "season": 1,
        "offset": 0,
        "title": "",
        "exclude": [],
        "totalEpisodeNumber": 0,
        "match": [],
        "type": "anime-garden",
        "bgmUrl": `https://bgm.tv/subject/${item[0].bgmId}`,
        "subgroup": item[0].name
      }

      ani = (await http.rssToAni(ani)).data
      if (item.length > 1) {
        ani.standbyRssList = item.slice(1)
            .map(o => {
              return {
                label: o.name,
                url: o['rss'],
                offset: 0
              }
            })
      }
      batchAdditionNum.value += item.length
      await http.addAni(ani)
    }
    ElMessage.success("添加成功")

    setTimeout(() => {
      location.reload()
    }, 1000)
  } catch (e) {
    ElMessage.error(e)
  } finally {
    batchAdditionDialogVisible.value = false
  }
}

let copy = (v) => {
  const input = document.createElement('input');
  input.value = v;
  document.body.appendChild(input);
  input.select();
  document.execCommand('copy');
  document.body.removeChild(input);
  ElMessage.success('已复制')
}

</script>

<style scoped>
.el-collapse {
  --el-collapse-header-height: 55px;
}

.match-item {
  margin-right: 12px;
  display: inline;
}

.tag-margin {
  margin-right: 4px;
}

.dialog-footer {
  display: flex;
  width: 100%;
  justify-content: end;
}

.content-wrapper {
  min-height: 300px;
}

.search-section {
  margin: 4px;
}

.season-selector {
  margin-top: 8px;
  width: 100%;
  justify-content: flex-end;
}

.season-select {
  max-width: 140px;
}

.scroll-container {
  margin: 8px 0 4px 0;
  height: 600px;
}

.week-tabs {
  margin: 0 4px;
}

.collapse-content {
  margin-left: 15px;
}

.collapse-title {
  align-items: center;
}

.title-text {
  margin-left: 6px;
  line-height: 1.6;
  font-weight: bold;
}

.score-margin {
  margin-left: 4px;
}

.score-color {
  color: #E800A4;
}

.badge-margin {
  margin-left: 4px;
}

.group-content {
  margin-left: 15px;
  min-height: 50px;
}

.group-title-wrapper {
  width: 100%;
  display: flex;
  justify-content: space-between;
}

.group-checkbox-wrapper {
  height: 100%;
}

.checkbox-margin {
  margin-right: 8px;
}

.group-label {
  display: flex;
  align-items: center;
  flex: 1;
  text-align: start;
}

.group-action {
  display: flex;
  align-items: center;
  margin-right: 14px;
  margin-left: 4px;
}

.group-items {
  margin-left: 15px;
}

.item-margin {
  margin-bottom: 4px;
}

.item-footer {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.cover {
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  width: 45px;
  height: 45px;
  object-fit: cover;
  flex-shrink: 0;
}

.match-content {
  max-width: 500px;
  min-width: 200px;
  margin-bottom: 4px;
}
</style>
