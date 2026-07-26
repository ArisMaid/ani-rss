<template>
  <el-config-provider
      :locale="zhCn"
      :link="linkConfig"
      :dialog="dialogConfig">
    <Login v-if="!authorization"/>
    <App v-else/>
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {authorization, init} from "@/js/global.js";
import {defineAsyncComponent, reactive} from "vue";

const Login = defineAsyncComponent(() => import('@/Login.vue'))
const App = defineAsyncComponent(() => import('@/home/App.vue'))

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

</script>
