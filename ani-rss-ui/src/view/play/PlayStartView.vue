<template>
  <el-dialog v-model="dialogVisible" :title="playItem.name" center @close="onClose">
    <div class="flex-center content">
      <div v-if="dialogVisible" class="player-content">
        <el-radio-group v-model="playerKind" aria-label="播放器" size="small" class="player-kind"><el-radio-button value="wasm">WASM 播放器</el-radio-button><el-radio-button value="standard">标准播放器</el-radio-button></el-radio-group>
        <MoviPlayerView v-if="playerKind === 'wasm'" :play-item="playItem"/>
        <ArtplayerView v-else :playItem="playItem"/>
      </div>
      <el-text v-if="subtitleLoading" class="subtitle-status" type="info">
        正在读取内封字幕…
      </el-text>
      <el-text v-if="subtitleError" class="subtitle-status" type="warning">
        {{ subtitleError }}
      </el-text>
    </div>
  </el-dialog>
</template>
<script setup>

import {defineAsyncComponent, onBeforeUnmount, ref, watch} from "vue";
import {useLocalStorage} from '@vueuse/core'
import ArtplayerView from "./ArtplayerView.vue";
const MoviPlayerView = defineAsyncComponent(() => import('./MoviPlayerView.vue'))
import {toApiMedia} from "@/js/global.js";
import * as http from "@/js/http.js";

let dialogVisible = ref(false)
let playItem = ref({})
let subtitleLoading = ref(false)
let subtitleError = ref('')
let subtitleRequest
let generation = 0
const playerKind = useLocalStorage('play-engine', 'wasm')
if(!['wasm','standard'].includes(playerKind.value)) playerKind.value = 'wasm'
let sidecarSubtitles = []

const revokeSubtitles = subtitles => {
  for (const subtitle of subtitles || []) {
    if (subtitle?.url?.startsWith('blob:')) {
      URL.revokeObjectURL(subtitle.url)
    }
  }
}

let show = (pi) => {
  onClose()
  const currentGeneration = ++generation
  const subtitles = (pi.subtitles || []).map(subtitle => ({...subtitle}))
  playItem.value = {...pi, subtitles};
  for (let subtitle of playItem.value.subtitles) {
    subtitle.url = toApiMedia(subtitle.url)
  }

  playItem.value.src = toApiMedia(playItem.value.filename)
  dialogVisible.value = true
  sidecarSubtitles = playItem.value.subtitles.map(subtitle => ({...subtitle}))
  if(playerKind.value === 'standard') loadInternalSubtitles(currentGeneration)
}

const loadInternalSubtitles = currentGeneration => {
  subtitleLoading.value = true
  subtitleError.value = ''
  subtitleRequest?.abort()
  subtitleRequest = new AbortController()
  // 获取内封字幕
  http.getSubtitles(playItem.value.filename, {signal: subtitleRequest.signal})
      .then(res => {
        if (currentGeneration !== generation || !dialogVisible.value) return
        const added = []
        for (let sub of res.data) {
          const blob = new Blob([sub.content], {type: "text/plain"});
          sub.url = URL.createObjectURL(blob);
          added.push(sub)
        }
        playItem.value.subtitles = [...playItem.value.subtitles, ...added]
      })
      .catch(error => {
        if (error?.code !== 'REQUEST_ABORTED' && currentGeneration === generation) {
          subtitleError.value = '内封字幕加载失败，可继续播放视频'
        }
      })
      .finally(() => {
        if (currentGeneration === generation) {
          subtitleLoading.value = false
        }
      });
}

watch(playerKind, kind => {
  if(!dialogVisible.value) return
  generation++; subtitleRequest?.abort(); subtitleLoading.value = false; subtitleError.value = ''
  revokeSubtitles(playItem.value.subtitles)
  playItem.value.subtitles = sidecarSubtitles.map(subtitle => ({...subtitle}))
  if(kind === 'standard') loadInternalSubtitles(generation)
})

defineExpose({
  show
})

let onClose = () => {
  generation++
  subtitleRequest?.abort()
  subtitleRequest = undefined
  revokeSubtitles(playItem.value.subtitles)
  dialogVisible.value = false
  subtitleLoading.value = false
  subtitleError.value = ''
  playItem.value = {}
  sidecarSubtitles = []
}

onBeforeUnmount(onClose)
</script>

<style scoped>
.content {
  width: 100%;
  max-height: 500px;
  min-height: 200px;
}

.subtitle-status {
  margin-left: 8px;
}
.player-content { min-width:0; }
.player-kind { margin-bottom:12px; }
</style>
