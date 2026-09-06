<template>
  <div>
    <div class="art-app"></div>
    <div class="flex" style="justify-content: end;">
      <el-dropdown>
        <el-button bg text icon="MoreFilled"/>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="externalUrl(src => `potplayer://${src}`)">
              <el-text>
                <el-icon>
                  <img alt="PotPlayer" class="el-icon--left icon" src="@/icon/icon-PotPlayer.webp"/>
                </el-icon>
                Pot
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item @click="externalUrl(src => `vlc://${src}`)">
              <el-text>
                <el-icon>
                  <img alt="VLC" class="el-icon--left icon" src="@/icon/icon-VLC.webp"/>
                </el-icon>
                VLC
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="externalUrl(src => `iina://weblink?url=${encodeUrl(src)}&mpv_force-media-title=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="IINA" class="el-icon--left icon" src="@/icon/icon-IINA.webp"/>
                </el-icon>
                IINA
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item @click="externalUrl(src => `mpvplay://${src}&mpv_force-media-title=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="MPV" class="el-icon--left icon" src="@/icon/icon-MPV.webp"/>
                </el-icon>
                MPV
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="externalUrl(src => `infuse://x-callback-url/play?url=${encodeUrl(src)}&filename=${encodeUrl(playItem.name)}`)">
              <el-text>
                <el-icon>
                  <img alt="Infuse" class="el-icon--left icon" src="@/icon/icon-Infuse.png"/>
                </el-icon>
                Infuse
              </el-text>
            </el-dropdown-item>
          </el-dropdown-menu>
          <el-dropdown-item @click="externalUrl(src => `ddplay:${encodeUrl(src)}|filePath=${encodeUrl(playItem.name)}`)">
            <el-text>
              <el-icon>
                <img alt="DandanPlay" class="el-icon--left icon" src="@/icon/icon-DandanPlay.webp"/>
              </el-icon>
              弹弹Play
            </el-text>
          </el-dropdown-item>
          <el-dropdown-item @click="externalUrl(src => `anix://openVideo/${encodeUrl(src)}`)">
            <el-text>
              <el-icon>
                <img alt="AnimacX" class="el-icon--left icon" src="@/icon/icon-AnimacX.webp"/>
              </el-icon>
              AnimacX
            </el-text>
          </el-dropdown-item>
          <el-dropdown-item
              @click="externalUrl(src => `SenPlayer://x-callback-url/play?url=${encodeUrl(src)}&name=${encodeUrl(playItem.name)}`)">
            <el-text>
              <el-icon>
                <img alt="SenPlayer" class="el-icon--left icon" src="@/icon/icon-SenPlayer.webp"/>
              </el-icon>
              SenPlayer
            </el-text>
          </el-dropdown-item>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup>
import {onBeforeUnmount, onMounted, watch} from 'vue'
import Artplayer from 'artplayer';
import artplayerPluginMultipleSubtitles from 'artplayer-plugin-multiple-subtitles';
import {ElMessage} from 'element-plus';
import {toApiMedia} from '@/js/global.js';
import * as http from '@/js/http.js'

const props = defineProps(['playItem'])

let openUrl = (url) => {
  window.open(url)
}

let externalUrl = async (builder) => {
  try {
    const response = await http.externalMediaHandle(props.playItem.filename)
    const handle = response?.data?.handle
    if (!handle) throw new Error('媒体句柄已过期')
    openUrl(builder(toApiMedia(handle)))
  } catch (error) {
    ElMessage.error(error?.message || '无法生成外部播放地址')
  }
}

let encodeUrl = (str) => {
  return encodeURIComponent(str);
}

let art = null

const destroyPlayer = () => {
  if (!art) return
  try {
    art.destroy(true);
  } catch (e) {
  }
  art = null
}

const createPlayer = () => {
  if (!props.playItem?.src) return
  let {src, subtitles = [], extName} = props['playItem'];
  subtitles = subtitles.map(subtitle => ({...subtitle}))
  let defaultName = ''
  let settings = []
  if (subtitles.length) {
    subtitles[0]['default'] = true
    defaultName = subtitles[0].name
    settings = [
      {
        width: 200,
        html: 'Subtitle',
        tooltip: defaultName,
        selector: subtitles,
        onSelect: function (item) {
          art.plugins['multipleSubtitles'].tracks([item.name]);
          return item.html;
        },
      },
    ]
  }
  art = new Artplayer({
    container: '.art-app',
    url: src,
    type: extName,
    theme: '#646cff',
    playbackRate: true,
    aspectRatio: true,
    screenshot: true,
    setting: true,
    pip: true,
    fullscreen: true,
    fullscreenWeb: true,
    airplay: true,
    preload: true,
    plugins: [
      artplayerPluginMultipleSubtitles({
        subtitles: subtitles
      })
    ],
    settings: settings
  });
  art.on('video:canplay', () => {
    if (defaultName) {
      art.plugins['multipleSubtitles'].tracks([defaultName]);
    }
  });
}

onMounted(createPlayer)

watch(() => props.playItem?.subtitles, subtitles => {
  if (!art || !subtitles?.length) return
  // The multiple-subtitles plugin builds one merged VTT when it is created;
  // rebuild only after the non-blocking internal subtitle request completes.
  destroyPlayer()
  createPlayer()
}, {deep: true})

onBeforeUnmount(destroyPlayer)
</script>

<style scoped>
.art-app {
  width: 700px;
  height: 450px;
  max-width: calc(100vw - 48px);
  max-height: calc(56.25vw - 27px);
  margin-bottom: 8px;
}

.icon {
  height: 14px;
  width: 14px;
}
</style>
