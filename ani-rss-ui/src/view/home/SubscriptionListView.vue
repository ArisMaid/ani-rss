<template>
  <component v-if="dialogs.edit" :is="EditAniView" ref="editAniRef"/>
  <component v-if="dialogs.playlist" :is="PlayListView" ref="playListRef"/>
  <component v-if="dialogs.cover" :is="CoverView" ref="coverRef"/>
  <component v-if="dialogs.delete" :is="DelAniView" ref="delAniRef"/>
  <component v-if="dialogs.rate" :is="BgmRateView" ref="bgmRateRef"/>
  <div class="list-container" v-loading="loading">
    <el-scrollbar class="hide-scrollbar">
      <div class="list-content">
        <template v-if="showWeek">
          <div v-for="weekItem in filterList" :key="weekItem.weekLabel">
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
            <div v-for="item in flatFilterList" :key="item.id">
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
  </div>
</template>

<script setup>
import {computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref} from "vue";
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

const changeFilterList = (text = '') => {
  let tempList = weekList.value;
  tempList = JSON.parse(JSON.stringify(tempList))

  const filter = item => {
    if (text.length < 1) {
      return true
    }
    let {title, pinyin, pinyinInitials} = item
    return title.indexOf(text) > -1 ||
        pinyin.indexOf(text) > -1 ||
        pinyinInitials.indexOf(text) > -1;
  }

  filterList.value = tempList
      .map(it => {
        let items = it.items;
        items = items
            .filter(props.filter)
            .filter(filter)
            .map(it => {
              return {...it, lastDownloadFormat: fromNow(it['lastDownloadTime'])}
            });
        return {
          weekLabel: it.weekLabel,
          items
        }
      })
      .filter(it => it.items.length)

  // 当不按星期展示时，展平并排序
  flatFilterList.value = Array.from(filterList.value)
      .flatMap(it => it.items)
      .sort((a, b) => a.sort - b.sort)
}

let listAbortController
let listGeneration = 0

const getList = () => {
  listAbortController?.abort()
  const controller = new AbortController()
  listAbortController = controller
  const generation = ++listGeneration
  loading.value = true

  return listAni({signal: controller.signal})
      .then(res => {
        if (generation !== listGeneration) return null
        let data = res.data
        weekList.value = data.weekList
        releaseDateList.value = data.releaseDateList
        emit('loaded', {
          releaseDateList: releaseDateList.value,
          total: weekList.value.reduce((total, week) => total + week.items.length, 0),
          refresh: data.refresh
        })

        changeFilterList(props.title)
        return data
      })
      .catch(error => {
        if (error?.code !== 'REQUEST_ABORTED') {
          return null
        }
        return null
      })
      .finally(() => {
        if (generation === listGeneration) loading.value = false
      })
}

onMounted(() => {
  window.$reLoadList = getList
  void getList()
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
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.hide-scrollbar {
  flex: 1;
  min-height: 0;
}

.list-content {
  margin: 0;
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
