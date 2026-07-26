<template>
  <el-dialog v-model="batchAdditionDialogVisible" align-center center title="正在批量添加订阅"
             width="500"
             :close-on-click-modal="false"
             :close-on-press-escape="false"
             :show-close="false">
    <div>
      <el-progress :percentage="Math.round((batchAdditionNum / rssList.length) * 100.0)"/>
    </div>
    <div>
      {{ batchAdditionNum }} / {{ rssList.length }}
    </div>
  </el-dialog>
  <el-dialog v-model="matchDialogVisible" align-center center title="匹配" width="auto">
    <div class="match-content">
      <el-radio-group v-model="addAni.match">
        <div v-for="regexItems in regexList" :key="JSON.stringify(regexItems)" class="match-item">
          <el-radio :label="JSON.stringify(regexItems)"
                    :value="JSON.stringify(regexItems.map(it => it.regex))">
            <template v-if="regexItems.length">
              <el-tag v-for="regexItem in regexItems" :key="regexItem.regex || regexItem.label" class="tag-margin">
                {{ regexItem.label }}
              </el-tag>
            </template>
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
  <el-dialog v-model="dialogVisible" center title="Mikan">
    <el-checkbox-group v-model="rssList">
      <div class="content-wrapper">
        <div class="search-section">
          <div class="search-header">
            <el-input v-model:model-value="text" clearable placeholder="请输入搜索标题"
                      prefix-icon="Search"
                      @clear="()=>{
            text = ''
            search()
          }"
                      @keyup.enter="search"></el-input>
            <div class="spacer"></div>
            <el-button :loading="searchLoading" bg icon="Search" text @click="search">搜索</el-button>
          </div>
          <div v-if="data.seasons.length" class="flex season-selector">
            <el-select v-model:model-value="seasonSelect" class="season-select"
                       :disabled="text.length > 0 || loading"
                       @change="change">
              <el-option v-for="season in data.seasons" :key="season['seasonLabel']"
                         :label="season['seasonLabel']" :value="season['seasonLabel']"/>
            </el-select>
            <el-button :disabled="rssList.length < 1" bg icon="Plus" text @click="batchAddition">批量添加</el-button>
          </div>
        </div>
        <div v-loading="loading" class="scroll-container">
          <el-scrollbar>
            <el-collapse v-model="activeName">
              <el-collapse-item v-for="week in data.weeks" :key="week.weekLabel" :name="week.weekLabel">
                <template #title>
                  <span style="margin-left: 4px;font-weight: bold;">
                    {{ week.weekLabel }}
                  </span>
                </template>
                <div class="collapse-content">
                  <el-collapse accordion @change="collapseChange">
                    <el-collapse-item v-for="it in week.items" :key="it.url" :name="it.url">
                      <template #title>
                        <div class="flex collapse-title">
                          <SafeImage :src-url="it['cover']" class="cover" @click.stop="open(it.url)"/>
                          <div class="flex collapse-title">
                            <el-text :truncated="false" line-clamp="1" size="small"
                                     class="title-text">
                              {{ it.title }}
                            </el-text>
                          </div>
                          <div v-if="it['score'] > 0" class="score-margin">
                            <h4 class="score-color">
                              {{ it['score'].toFixed(1) }}
                            </h4>
                          </div>
                          <el-badge v-if="it['exists']" class="item badge-margin" type="primary"
                                    value="已订阅"/>
                        </div>
                      </template>
                      <div v-if="selectName === it.url" v-loading="groupLoading"
                           class="group-content">
                        <el-collapse accordion>
                          <el-collapse-item v-for="group in groups[it.url]"
                                            :key="group.url || group.label || JSON.stringify(group.groupRegex)">
                            <template #title>
                              <div class="group-title-wrapper">
                                <div class="group-checkbox-wrapper">
                                  <el-checkbox :value="JSON.stringify(group)" class="checkbox-margin" @click.stop/>
                                </div>
                                <div class="group-label">
                                  <el-text style="max-width: 100px;" truncated>{{ group.label }}</el-text>
                                  &nbsp;
                                  <el-text class="mx-1" size="small">{{ group['updateDay'] }}</el-text>
                                </div>
                                <div v-if="showTag()">
                                  <el-tag v-for="tag in group['groupRegex']['tags']" :key="tag"
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
                              <div v-for="ti in group.items" :key="ti.magnet || ti.torrent || ti.title"
                                   class="item-margin">
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
                                        <el-button :icon="DocumentCopy" bg text @click="copy(ti['magnet'])"/>
                                        <el-button :icon="DownloadIcon" bg text @click="openUrl(ti['torrent'])"/>
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
              </el-collapse-item>
            </el-collapse>
          </el-scrollbar>
        </div>
      </div>
    </el-checkbox-group>
  </el-dialog>
</template>

<script setup>
import {ref} from "vue";
import {ElMessage, ElText} from "element-plus";
import {DocumentCopy, Download as DownloadIcon} from "@element-plus/icons-vue";
import SafeImage from '@/other/SafeImage.vue'
import {openHttpUrl} from '@/js/url.js'
import * as http from "@/js/http.js";

// 批量添加订阅
let rssList = ref([]);

let groupLoading = ref(false)
let activeName = ref("")
let dialogVisible = ref(false)
let loading = ref(false)
let data = ref({
  'seasons': [],
  'items': [],
  'weeks': []
})

let seasonSelect = ref('')
let selectName = ref('')
let groups = ref({})
let listRequestId = 0
let groupRequestId = 0
let scoreRequestId = 0

let resetListInteractionState = () => {
  activeName.value = ''
  selectName.value = ''
  groups.value = {}
  rssList.value = []
  groupRequestId += 1
}

let show = (name) => {
  listRequestId += 1
  seasonSelect.value = ''
  dialogVisible.value = true
  text.value = ''
  data.value = {
    'seasons': [],
    'items': [],
    'weeks': []
  }
  resetListInteractionState()
  if (name) {
    name = name.replace(/ ?\((19|20)\d{2}\)/g, "").trim()
    name = name.replace(/ ?\[tmdbid=(\d+)]/g, "").trim()
    if (name.length > 2) {
      text.value = name
      search()
      return
    }
  }
  list({})
}

let text = ref('')

let searchLoading = ref(false)
let search = () => {
  if (text.value.length === 1) {
    ElMessage.error("搜索最少需要两个字符")
    return
  }
  searchLoading.value = true
  list({}, text.value).finally(() => {
    searchLoading.value = false
  })
}

let list = async (body, text) => {
  let requestId = ++listRequestId
  let currentScoreRequestId = ++scoreRequestId
  loading.value = true
  text = text ? text : ''
  body = body ? body : {}
  resetListInteractionState()
  data.value.weeks = []
  if (text) {
    data.value.seasons = []
    seasonSelect.value = ''
  }
  return http.mikan(text, body)
      .then(res => {
        if (requestId !== listRequestId) {
          return
        }
        let {seasons = [], weeks = [], totalItem, totalItems} = res.data || {};
        seasons = Array.isArray(seasons) ? seasons : []
        weeks = Array.isArray(weeks) ? weeks : []
        const itemCount = Number.isFinite(totalItem)
            ? totalItem
            : Number.isFinite(totalItems)
                ? totalItems
                : weeks.reduce((count, week) => count + (Array.isArray(week?.items) ? week.items.length : 0), 0)

        if (itemCount < 1) {
          ElMessage.warning("搜索结果为空")
        }

        if (seasons.length || text) {
          data.value.seasons = seasons
        }
        data.value.weeks = weeks
        enrichScores(requestId, currentScoreRequestId)
        if (weeks.length) {
          activeName.value = weeks[0].weekLabel
        }
        for (let season of data.value.seasons) {
          if (season['select'] && !seasonSelect.value) {
            seasonSelect.value = season['seasonLabel']
            return
          }
        }
      })
      .finally(() => {
        if (requestId === listRequestId) {
          loading.value = false
        }
      });
}

let enrichScores = (requestId, currentScoreRequestId) => {
  const mikanIds = [...new Set(
    data.value.weeks
        .flatMap(week => Array.isArray(week?.items) ? week.items : [])
        .map(item => String(item?.url || '').match(/\/Home\/Bangumi\/(\d+)\/?$/)?.[1])
        .filter(Boolean)
  )]
  if (!mikanIds.length) {
    return
  }

  http.mikanScores(mikanIds)
      .then(res => {
        if (requestId !== listRequestId || currentScoreRequestId !== scoreRequestId) {
          return
        }
        const scores = res?.data?.scores || {}
        const subscribedBgmIds = new Set(res?.data?.subscribedBgmIds || [])
        for (const week of data.value.weeks) {
          if (!Array.isArray(week?.items)) {
            continue
          }
          for (const item of week.items) {
            const mikanId = String(item?.url || '').match(/\/Home\/Bangumi\/(\d+)\/?$/)?.[1]
            const score = mikanId ? scores[mikanId] : null
            if (!score) {
              continue
            }
            item.score = Number(score.score) || 0
            item.bgmId = score.bgmId || item.bgmId
            if (item.bgmId && subscribedBgmIds.has(item.bgmId)) {
              item.exists = true
            }
          }
          week.items.sort((left, right) => Number(right.score || 0) - Number(left.score || 0))
        }
        data.value.weeks = [...data.value.weeks]
      })
      .catch(() => {
        // Scores are an optional enhancement; leave the season list usable.
      })
}

let change = (v) => {
  let body = data.value.seasons.find(item => item['seasonLabel'] === v)
  if (body) {
    list(body)
  }
}

let collapseChange = (v) => {
  if (!v) {
    selectName.value = ''
    groupRequestId += 1
    return
  }
  selectName.value = v
  if (groups.value[v]) {
    return;
  }
  let requestId = ++groupRequestId
  groupLoading.value = true
  http.mikanGroup(v)
      .then(res => {
        if (requestId === groupRequestId && selectName.value === v) {
          groups.value[v] = res.data
        }
      })
      .finally(() => {
        if (requestId === groupRequestId) {
          groupLoading.value = false
        }
      })
}


let matchDialogVisible = ref(false)

let addAni = ref({
  'bgmUrl': '',
  'url': '',
  'match': '',
  'group': '',
  'subgroup': ''
})

let regexList = ref([])

let callback = v => {
  let {rss, bgmUrl, label} = v
  regexList.value = JSON.parse(JSON.stringify(v.groupRegex.regexList))

  addAni.value.url = rss
  addAni.value.bgmUrl = bgmUrl
  addAni.value.subgroup = label
  addAni.value.match = '[]'

  regexList.value.push([])
  matchDialogVisible.value = true
}

let showTag = () => {
  return window.innerWidth > 900;
}

let open = url => {
  openHttpUrl(url);
}

defineExpose({show})

let emit = defineEmits(['callback'])


let batchAdditionNum = ref(0)
let batchAdditionDialogVisible = ref(false)

let batchAddition = async () => {
  batchAdditionNum.value = 0
  batchAdditionDialogVisible.value = true
  let getBangumiId = (url) => {
    const parsedUrl = new URL(url);
    return parsedUrl.searchParams.get('bangumiId');
  };

  try {
    ElMessage.success("添加中....")
    let map = rssList.value.reduce((acc, item) => {
      let bangumiId = getBangumiId(JSON.parse(item)['rss']);
      if (!acc[bangumiId]) {
        acc[bangumiId] = [];
      }
      acc[bangumiId].push(JSON.parse(item));
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
        "type": "mikan"
      }

      ani = (await http.rssToAni(ani, {silent: true})).data
      if (item.length > 1) {
        ani.standbyRssList = item.slice(1)
            .map(o => {
              return {
                label: o.label,
                url: o['rss'],
                offset: 0
              }
            })
      }
      batchAdditionNum.value += item.length
      await http.addAni(ani, {silent: true})
    }
    ElMessage.success("添加成功")

    setTimeout(() => {
      location.reload()
    }, 1000)
  } catch (e) {
    ElMessage.error(e?.message || '批量添加失败')
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

let openUrl = (url) => openHttpUrl(url)

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

.search-header {
  display: flex;
  justify-content: space-between;
}

.spacer {
  width: 4px;
}

.season-selector {
  margin-top: 4px;
  width: 100%;
  justify-content: space-between;
}

.season-select {
  max-width: 140px;
}

.scroll-container {
  margin: 8px 0 4px 0;
  height: 600px;
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
