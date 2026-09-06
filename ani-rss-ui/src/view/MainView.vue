<template>
  <el-config-provider
      :locale="zhCn"
      :link="linkConfig"
      :dialog="dialogConfig">
    <LoginView v-if="ready && !authorization"/>
    <MainLayoutView v-else-if="ready"/>
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {authorization, init, initAuth} from "@/js/global.js";
import {defineAsyncComponent, onMounted, reactive, ref} from "vue";

const LoginView = defineAsyncComponent(() => import('@/view/LoginView.vue'))
const MainLayoutView = defineAsyncComponent(() => import('@/view/home/MainLayoutView.vue'))

/**
 * 链接配置
 */
let linkConfig = reactive({
  type: 'primary',
  underline: 'never'
})

let dialogConfig = reactive({
  alignCenter: true
})

init()

const ready = ref(false)

onMounted(async () => {
  await initAuth()
  ready.value = true
})

</script>
