<template>
  <component v-if="dialogs.edit" :is="EditAniView" ref="editAniRef"/>
  <component v-if="dialogs.playlist" :is="PlayListView" ref="playListRef"/>
  <component v-if="dialogs.cover" :is="CoverView" ref="coverRef"/>
  <component v-if="dialogs.delete" :is="DelAniView" ref="delAniRef"/>
  <component v-if="dialogs.rate" :is="BgmRateView" ref="bgmRateRef"/>
  <div class="list-container" v-loading="loading">
    <el-scrollbar ref="scrollbarRef" class="subscription-scrollbar" always>
      <div class="list-content">
        <template v-if="showWeek">
          <div v-for="weekItem in visibleWeekList" :key="weekItem.groupKey">
            <h2 class="list-week-title">
              {{ weekItem.weekLabel }}
            </h2>
            <div :class="gridClass">
              <div v-for="item in weekItem.items" :key="item.id">
                <component
                    :is="viewComponent"
                    :item="item"
                    @edit="openDialog('edit', $event)"
                    @playlist="openDialog('playlist', $event)"
                    @cover="openDialog('cover', $event)"
                    @del="openDialog('delete', $event)"
                    @rate="openDialog('rate', $event)"
                />
              </div>
            </div>
          </div>
        </template>
        <template v-else>
          <div :class="gridClass">
            <div v-for="item in visibleFlatFilterList" :key="item.id">
              <component
                  :is="viewComponent"
                  :item="item"
                  @edit="openDialog('edit', $event)"
                  @playlist="openDialog('playlist', $event)"
                  @cover="openDialog('cover', $event)"
                  @del="openDialog('delete', $event)"
                  @rate="openDialog('rate', $event)"
              />
            </div>
          </div>
        </template>
        <div class="list-bottom-spacer"></div>
      </div>
    </el-scrollbar>
    <div v-if="totalCount" class="subscription-pagination">
      <div class="subscription-pagination-summary">
        <span>第 {{ rangeStart }}-{{ rangeEnd }} 项，共 {{ totalCount }} 项</span>
        <span v-if="showWeek">按星期分组，每页最多 {{ PAGE_SIZE }} 个订阅</span>
      </div>
      <el-pagination
          v-if="pageCount > 1"
          v-model:current-page="currentPage"
          :page-size="PAGE_SIZE"
          :total="totalCount"
          background
          layout="prev, pager, next"
          @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup>
import {
  computed,
  defineAsyncComponent,
  nextTick,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch
} from "vue";
import {fromNow} from "@/js/format.js";
import {listAni} from "@/js/http.js";
import AniCardView from "@/view/home/AniCardView.vue";
import AniCoverView from "@/view/home/AniCoverView.vue";
import {showWeek} from "@/js/global.js";

const props = defineProps({
  title: String,
  filter: Function,
  viewMode: {
    type: String,
    default: 'card'
  }
})
const emit = defineEmits(['loaded'])

const EditAniView = defineAsyncComponent(() => import("./EditAniView.vue"))
const PlayListView = defineAsyncComponent(() => import("@/view/play/PlayListView.vue"))
const CoverView = defineAsyncComponent(() => import("./CoverView.vue"))
const DelAniView = defineAsyncComponent(() => import("./DelAniView.vue"))
const BgmRateView = defineAsyncComponent(() => import("./BgmRateView.vue"))

const editAniRef = ref()
const delAniRef = ref()
const coverRef = ref()
const playListRef = ref()
const bgmRateRef = ref()
const dialogs = reactive({
  edit: false,
  playlist: false,
  cover: false,
  delete: false,
  rate: false
})

const weekList = ref([])
const filterList = ref([])
const flatFilterList = ref([])
const releaseDateList = ref([])

const loading = ref(true)
const scrollbarRef = ref()
const currentPage = ref(1)
const PAGE_SIZE = 60
const viewComponent = computed(() => props.viewMode === 'cover' ? AniCoverView : AniCardView)
const gridClass = computed(() => [
  'grid-container',
  props.viewMode === 'cover' ? 'cover-grid-container' : 'card-grid-container'
])

const dialogRefs = {
  edit: editAniRef,
  playlist: playListRef,
  cover: coverRef,
  delete: delAniRef,
  rate: bgmRateRef
}

const openDialog = (name, payload) => {
  dialogs[name] = true
  const startedAt = Date.now()
  const showWhenReady = () => {
    const show = dialogRefs[name]?.value?.show
    if (show) {
      show(payload)
      return
    }
    if (Date.now() - startedAt < 5_000) {
      setTimeout(showWhenReady, 16)
    }
  }
  void nextTick(showWhenReady)
}

const decorateItem = item => ({
  ...item,
  lastDownloadFormat: fromNow(item['lastDownloadTime'])
})

const filteredWeekEntries = computed(() =>
    filterList.value.flatMap((weekItem, weekIndex) =>
        weekItem.items.map(item => ({
          groupKey: weekItem.groupKey || String(weekIndex) + '-' + weekItem.weekLabel,
          weekLabel: weekItem.weekLabel,
          item
        }))
    )
)

const totalCount = computed(() =>
    showWeek.value ? filteredWeekEntries.value.length : flatFilterList.value.length
)
const pageCount = computed(() => Math.max(1, Math.ceil(totalCount.value / PAGE_SIZE)))
const pageOffset = computed(() => (currentPage.value - 1) * PAGE_SIZE)
const rangeStart = computed(() => totalCount.value ? pageOffset.value + 1 : 0)
const rangeEnd = computed(() =>
    totalCount.value ? Math.min(pageOffset.value + PAGE_SIZE, totalCount.value) : 0
)

const visibleFlatFilterList = computed(() =>
    flatFilterList.value
        .slice(pageOffset.value, pageOffset.value + PAGE_SIZE)
        .map(decorateItem)
)

const visibleWeekList = computed(() => {
  const groups = new Map()
  const pageEntries = filteredWeekEntries.value
      .slice(pageOffset.value, pageOffset.value + PAGE_SIZE)
  for (const entry of pageEntries) {
    let group = groups.get(entry.groupKey)
    if (!group) {
      group = {
        groupKey: entry.groupKey,
        weekLabel: entry.weekLabel,
        items: []
      }
      groups.set(entry.groupKey, group)
    }
    group.items.push(decorateItem(entry.item))
  }
  return [...groups.values()]
})

const scrollToTop = async () => {
  await nextTick()
  scrollbarRef.value?.setScrollTop?.(0)
}

const clampCurrentPage = ({scrollWhenClamped = false} = {}) => {
  const nextPage = Math.min(currentPage.value, pageCount.value)
  if (nextPage !== currentPage.value) {
    currentPage.value = nextPage
    if (scrollWhenClamped) {
      void scrollToTop()
    }
  }
}

const changeFilterList = (text = '', {resetPage = true} = {}) => {
  const filter = item => {
    const query = String(text ?? '')
    if (query.length < 1) {
      return true
    }
    let {title, pinyin, pinyinInitials} = item
    return String(title ?? '').indexOf(query) > -1 ||
        String(pinyin ?? '').indexOf(query) > -1 ||
        String(pinyinInitials ?? '').indexOf(query) > -1;
  }
  const itemFilter = typeof props.filter === 'function' ? props.filter : () => true

  filterList.value = weekList.value
      .map((it, index) => {
        const items = it.items
            .filter(itemFilter)
            .filter(filter)
        const groupKey = String(index) + '-' + it.weekLabel
        return {
          groupKey,
          weekLabel: it.weekLabel,
          items
        }
      })
      .filter(it => it.items.length)

  // 当不按星期展示时，展平并排序
  flatFilterList.value = Array.from(filterList.value)
      .flatMap(it => it.items)
      .sort((a, b) => a.sort - b.sort)

  if (resetPage) {
    currentPage.value = 1
    void scrollToTop()
  } else {
    clampCurrentPage({scrollWhenClamped: true})
  }
}

let listAbortController
let listGeneration = 0

const getList = ({background = false} = {}) => {
  listAbortController?.abort()
  const controller = new AbortController()
  listAbortController = controller
  const generation = ++listGeneration
  if (!background) {
    loading.value = true
  }

  return listAni({signal: controller.signal})
      .then(res => {
        if (generation !== listGeneration) return null
        let data = res.data
        weekList.value = data.weekList || []
        releaseDateList.value = data.releaseDateList || []
        emit('loaded', {
          releaseDateList: releaseDateList.value,
          total: weekList.value.reduce((total, week) => total + week.items.length, 0),
          refresh: data.refresh
        })

        changeFilterList(props.title, {resetPage: false})
        return data
      })
      .catch(() => null)
      .finally(() => {
        if (generation === listGeneration && !background) loading.value = false
      })
}

const handlePageChange = page => {
  currentPage.value = Math.max(1, Math.min(page, pageCount.value))
  void scrollToTop()
}

watch(() => props.title, value => changeFilterList(value))
watch(() => props.filter, () => changeFilterList(props.title))
watch(showWeek, () => changeFilterList(props.title))
watch(() => props.viewMode, () => void scrollToTop())

onMounted(() => {
  window.$reLoadList = getList
  void getList()
})

onDeactivated(() => {
  listGeneration++
  listAbortController?.abort()
  listAbortController = undefined
  loading.value = false
})

onBeforeUnmount(() => {
  listGeneration++
  listAbortController?.abort()
  if (window.$reLoadList === getList) delete window.$reLoadList
})

defineExpose({
  releaseDateList,
  changeFilterList,
  getList
})

</script>

<style scoped>
.grid-container {
  display: grid;
  grid-gap: 8px;
  width: 100%;
}

.list-container {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.subscription-scrollbar {
  flex: 1;
  min-width: 0;
  min-height: 0;
}

.list-content {
  margin: 0;
  min-width: 0;
}

.subscription-pagination {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  padding-top: 8px;
}

.subscription-pagination-summary {
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.list-week-title {
  margin-top: 12px;
  margin-bottom: 4px;
}

.list-bottom-spacer {
  height: 8px;
}

.card-grid-container {
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
}

.cover-grid-container {
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  grid-gap: 24px;
}

@media (max-width: 800px) {
  .card-grid-container {
    grid-template-columns: 1fr;
  }

  .cover-grid-container {
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    grid-gap: 12px;
  }
}
</style>
