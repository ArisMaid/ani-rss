<template>
  <el-dialog v-model="visible" title="请修改默认登录信息"
             width="min(440px, calc(100vw - 32px))" :close-on-click-modal="false">
    <p class="default-login-message">
      当前仍在使用默认用户名和密码，请及时前往「设置 → 安全」修改，并保存设置。
    </p>
    <el-checkbox v-model="hideReminder" class="default-login-preference">
      不再提醒
    </el-checkbox>
    <template #footer>
      <el-button @click="visible = false">稍后再说</el-button>
      <el-button type="primary" @click="emit('confirm')">立即修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
const visible = defineModel({type: Boolean, default: false})
const hideReminder = defineModel('hideReminder', {type: Boolean, default: false})
const emit = defineEmits(['confirm'])
</script>

<style scoped>
.default-login-message {
  margin: 0;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}

.default-login-preference {
  margin-top: 12px;
}
</style>
