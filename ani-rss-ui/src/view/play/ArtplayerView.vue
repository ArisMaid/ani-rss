<template>
  <div>
    <div class="art-app"></div>
    <ExternalPlayers :play-item="playItem"/>
  </div>
</template>

<script setup>
import {onBeforeUnmount, onMounted, watch} from 'vue'
import Artplayer from 'artplayer';
import artplayerPluginMultipleSubtitles from 'artplayer-plugin-multiple-subtitles';
import ExternalPlayers from "./ExternalPlayers.vue"

const props = defineProps(['playItem'])

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
