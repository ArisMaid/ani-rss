<template>
  <el-dialog v-model="dialogVisible" :title="playItem.name" center @close="onClose">
    <div class="flex-center content">
      <ArtplayerView :playItem="playItem" v-if="dialogVisible"/>
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

import {onBeforeUnmount, ref} from "vue";
import ArtplayerView from "./ArtplayerView.vue";
import {toApiMedia} from "@/js/global.js";
import * as http from "@/js/http.js";

let dialogVisible = ref(false)
let playItem = ref({})
let subtitleLoading = ref(false)
let subtitleError = ref('')
let subtitleRequest
let generation = 0

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
</style>
