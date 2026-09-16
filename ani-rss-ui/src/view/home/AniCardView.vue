<template>
  <el-card shadow="never">
    <div class="list-card-content">
      <div class="list-card-image-container">
        <img v-if="item['cover'] && !coverFailed"
             :src="toApiFile(item['cover'])"
             :alt="item.title"
             loading="lazy"
             decoding="async"
             class="list-card-image"
             @load="coverFailed = false"
             @error="coverFailed = true"/>
        <div v-else class="list-card-image list-card-image-placeholder">
          <el-icon><Picture/></el-icon>
          <span v-if="coverFailed">图片暂未加载</span>
        </div>
        <button
            type="button"
            class="list-card-cover-click-target"
            :aria-label="coverActionLabel"
            @click="handleCoverClick"
        ></button>
      </div>
      <div class="list-card-info">
        <div class="list-card-info-inner">
          <div class="flex">
            <el-tooltip :content="item.title" placement="top">
              <el-text :line-clamp="1"
                       @click="openBgmUrl(item)"
                       class="list-card-title"
                       truncated>
                {{ item.title }}
              </el-text>
            </el-tooltip>
          </div>
          <div class="list-card-score-container" v-if="showScore">
            <h4 class="list-card-score" @click="emit('rate', item)">
              {{ item['score'].toFixed(1) }}
            </h4>
          </div>
          <el-text v-else
                   line-clamp="2"
                   size="small"
                   class="list-card-url">
            {{ decodeURLComponentSafe(item.url) }}
          </el-text>
          <div class="list-card-tags">
            <el-tag>
              第 {{ item.season }} 季
            </el-tag>
            <el-tag type="success" v-if="item.enable">
              已启用
            </el-tag>
            <el-tag type="info" v-else>
              未启用
            </el-tag>
            <el-tag type="info" :title="item['subgroup'] || '未知字幕组'">
              <el-text line-clamp="1" size="small" class="list-card-subgroup">
                {{ item['subgroup'] ? item['subgroup'] : '未知字幕组' }}
              </el-text>
            </el-tag>
            <el-tag type="warning">
              {{ item['currentEpisodeNumber'] }} /
              {{ item['totalEpisodeNumber'] ? item['totalEpisodeNumber'] : '*' }}
            </el-tag>
            <el-tag type="danger" v-if="item.ova">
              ova
            </el-tag>
            <el-tag type="danger" v-else>
              tv
            </el-tag>
            <el-tag v-if="item.standbyRssList.length > 0">
              备用RSS
            </el-tag>
          </div>
          <el-text v-if="showLastDownloadTime && item.lastDownloadTime > 0" size="small"
                   type="info">
            {{ item.lastDownloadFormat }}
          </el-text>
        </div>
        <div class="list-card-actions">
          <el-button text @click="emit('playlist', item)" bg v-if="showPlaylist">
            <el-icon>
              <Files/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer" v-if="showPlaylist"></div>
          <el-button bg text title="更换封面" @click="emit('cover', item)">
            <el-icon>
              <Picture/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer"></div>
          <el-button bg text @click="emit('edit', item)">
            <el-icon>
              <EditIcon/>
            </el-icon>
          </el-button>
          <div class="list-card-spacer"></div>
          <el-button type="danger" text @click="emit('del', [item])" bg>
            <el-icon>
              <Delete/>
            </el-icon>
          </el-button>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import {coverClickAction, showLastDownloadTime, showPlaylist, showScore, toApiFile} from "@/js/global.js";
import {computed, ref, watch} from "vue";
import {Delete, Edit as EditIcon, Files, Picture} from "@element-plus/icons-vue";

const props = defineProps(["item"])
const coverFailed = ref(false)
const emit = defineEmits(['edit', 'playlist', 'cover', 'del', 'rate'])
watch(() => props.item.cover, () => {
  coverFailed.value = false
})

let openBgmUrl = (it) => {
  if (it.bgmUrl?.length) {
    window.open(it.bgmUrl, '_blank', 'noopener')
    return
  }
  if (it.title?.length) {
    let title = it.title.replace(/ ?\((19|20)\d{2}\)/g, "").trim()
    title = title.replace(/ ?\[tmdbid=(\d+)]/g, "").trim()
    window.open(`https://bgm.tv/subject_search/${encodeURIComponent(title)}?cat=2`, '_blank', 'noopener')
  }
}

let decodeURLComponentSafe = (str) => {
  return decodeURIComponent(str.replace('+', ' '));
}

const coverActionLabel = computed(() => {
  const labels = {
    edit: '编辑订阅',
    playlist: '打开视频列表',
    cover: '编辑封面'
  }
  return `${labels[coverClickAction.value] || labels.cover}：${props.item.title || '当前订阅'}`
})

const handleCoverClick = () => {
  const action = ['edit', 'playlist', 'cover'].includes(coverClickAction.value)
      ? coverClickAction.value
      : 'cover'
  emit(action, props.item)
}
</script>

<style scoped>
.list-card-content {
  display: flex;
  width: 100%;
  align-items: center;
}

.list-card-image-container {
  position: relative;
  height: 100%;
}

.list-card-cover-click-target {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  border-radius: var(--el-border-radius-base);
  background: transparent;
  cursor: pointer;
}

.list-card-cover-click-target:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}

.list-card-image {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  height: 130px;
  width: 92px;
}

.list-card-image-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  color: var(--el-text-color-secondary);
  font-size: 24px;
  text-align: center;
}

.list-card-image-placeholder span {
  font-size: 11px;
}

.list-card-info {
  flex-grow: 1;
  position: relative;
}

.list-card-info-inner {
  margin-left: 8px;
}

.list-card-title {
  width: 200px;
  line-height: 1.6;
  letter-spacing: 0.0125em;
  font-weight: 500;
  font-size: 0.97em;
  cursor: pointer;
  color: var(--el-text-color-primary);
}

.list-card-score-container {
  margin-bottom: 8px;
}

.list-card-score {
  color: #E800A4;
  cursor: pointer;
}

.list-card-url {
  max-width: 300px;
}

.list-card-tags {
  width: 180px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  grid-gap: 4px;
}

.list-card-subgroup {
  max-width: 60px;
  color: var(--el-color-info);
}

.list-card-actions {
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  flex-direction: column;
  position: absolute;
  right: 0;
  bottom: 0;
}

.list-card-spacer {
  height: 5px;
}

@media (max-width: 800px) {
  .list-card-tags {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
