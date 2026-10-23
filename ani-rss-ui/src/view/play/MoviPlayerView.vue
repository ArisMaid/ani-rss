<template>
  <div class="movi-container">
    <MoviPlayer class="movi-app" :src="playItem.src" :controls="true" :wasmurl="wasmUrl" @error="failed = true">
      <MoviTrack v-for="(subtitle, index) in playItem.subtitles || []" :key="subtitle.url"
                 :src="subtitle.url" kind="subtitles" :label="subtitle.name || `字幕 ${index + 1}`"
                 :format="subtitle.type || 'vtt'" :default="index === 0"/>
    </MoviPlayer>
    <el-text v-if="failed" type="warning">播放失败，可切换标准播放器或使用外部播放器。</el-text>
    <ExternalPlayers :play-item="playItem"/>
  </div>
</template>
<script setup>
import {ref} from 'vue'
import {MoviPlayer, MoviTrack} from 'movi-player/vue/slim'
import wasmUrl from 'movi-player/movi.wasm?url'
import ExternalPlayers from './ExternalPlayers.vue'
defineProps(['playItem'])
const failed = ref(false)
</script>
<style scoped>
.movi-app { display:block; width:700px; height:450px; max-width:calc(100vw - 48px); max-height:calc(56.25vw - 27px); margin-bottom:8px; }
.movi-container { min-width:0; }
</style>
