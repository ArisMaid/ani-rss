<template>
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
</template>
<script setup>
import {ElMessage} from "element-plus"
import {toApiMedia} from "@/js/global.js"
import * as http from "@/js/http.js"
const props = defineProps(["playItem"])
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


</script>
<style scoped>.icon {height:14px;width:14px;}</style>
