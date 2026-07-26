<template>
  <div>
    <div class="art-app"></div>
    <div class="flex" style="justify-content: end;">
      <el-dropdown>
        <el-button bg text icon="MoreFilled"/>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="openExternal(src => `potplayer://${src}`)">
              <el-text>
                <el-icon>
                  <img alt="PotPlayer" class="el-icon--left icon" src="../icon/icon-PotPlayer.webp"/>
                </el-icon>
                Pot
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item @click="openExternal(src => `vlc://${src}`)">
              <el-text>
                <el-icon>
                  <img alt="VLC" class="el-icon--left icon" src="../icon/icon-VLC.webp"/>
                </el-icon>
                VLC
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="openExternal(src => withQuery('iina://weblink', {url: src, 'mpv_force-media-title': playItem.name}))">
              <el-text>
                <el-icon>
                  <img alt="IINA" class="el-icon--left icon" src="../icon/icon-IINA.webp"/>
                </el-icon>
                IINA
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item @click="openExternal(src => withQuery(`mpvplay://${src}`, {'mpv_force-media-title': playItem.name}))">
              <el-text>
                <el-icon>
                  <img alt="MPV" class="el-icon--left icon" src="../icon/icon-MPV.webp"/>
                </el-icon>
                MPV
              </el-text>
            </el-dropdown-item>
            <el-dropdown-item
                @click="openExternal(src => withQuery('infuse://x-callback-url/play', {url: src, filename: playItem.name}))">
              <el-text>
                <el-icon>
                  <img alt="Infuse" class="el-icon--left icon" src="../icon/icon-Infuse.png"/>
                </el-icon>
                Infuse
              </el-text>
            </el-dropdown-item>
          </el-dropdown-menu>
          <el-dropdown-item @click="openExternal(src => `ddplay:${encodeUrl(src)}|filePath=${encodeUrl(playItem.name)}`)">
            <el-text>
              <el-icon>
                <img alt="DandanPlay" class="el-icon--left icon" src="../icon/icon-DandanPlay.webp"/>
              </el-icon>
              弹弹Play
            </el-text>
          </el-dropdown-item>
          <el-dropdown-item @click="openExternal(src => `anix://openVideo/${encodeUrl(src)}`)">
            <el-text>
              <el-icon>
                <img alt="AnimacX" class="el-icon--left icon" src="../icon/icon-AnimacX.webp"/>
              </el-icon>
              AnimacX
            </el-text>
          </el-dropdown-item>
          <el-dropdown-item
              @click="openExternal(src => withQuery('SenPlayer://x-callback-url/play', {url: src, name: playItem.name}))">
            <el-text>
              <el-icon>
                <img alt="SenPlayer" class="el-icon--left icon" src="../icon/icon-SenPlayer.webp"/>
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
import {onBeforeUnmount, onMounted} from 'vue'
import Artplayer from 'artplayer';
import artplayerPluginMultipleSubtitles from 'artplayer-plugin-multiple-subtitles';
import * as http from '@/js/http.js'
import {toApiMedia} from '@/js/global.js'

const props = defineProps(['playItem'])

let openUrl = (url) => {
  window.open(url)
}

let externalSrc = ''
let openExternal = async (buildUrl) => {
  if (!externalSrc) {
    const response = await http.externalMediaHandle(props.playItem.filename)
    externalSrc = toApiMedia(response.data.handle)
  }
  openUrl(buildUrl(externalSrc))
}

let withQuery = (base, params) => {
  const url = new URL(base)
  url.search = new URLSearchParams(params).toString()
  return url.toString()
}

let encodeUrl = (str) => {
  return encodeURIComponent(str);
}

let art = null

onMounted(() => {
  let {src, subtitles, extName} = props['playItem'];
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
    moreVideoAttr: {preload: 'auto'},
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
})

onBeforeUnmount(() => {
  if (!art) {
    return
  }
  try {
    art.destroy(true);
    art = null;
  } catch {
    // The player can already be detached during dialog teardown.
  }
})
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
