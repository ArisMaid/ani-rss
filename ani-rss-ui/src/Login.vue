<template>
  <div
      class="flex-center content">
    <div id="login-page" class="flex-center">
      <div id="form">
        <div style="text-align: center;">
          <img src="../public/icon.svg" height="80" width="80" alt="icon.svg"/>
        </div>
        <h2 class="title-h2">{{ setupMode ? '首次设置' : 'ANI-RSS' }}</h2>
        <el-form @submit.prevent
                 @keyup.enter="submit">
          <el-form-item v-if="setupMode">
            <div>
              <el-input v-model.trim="setupCode" placeholder="一次性设置码" autocomplete="one-time-code">
                <template #prefix><el-icon><Key/></el-icon></template>
              </el-input>
              <el-text size="small">设置码位于配置目录的 initial-setup-code.txt</el-text>
            </div>
          </el-form-item>
          <el-form-item>
            <el-input v-model.trim="user.username"
                      placeholder="用户名" autocomplete="username">
              <template #prefix>
                <el-icon class="el-input__icon">
                  <User/>
                </el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input v-model="user.password" show-password
                      placeholder="密码" autocomplete="current-password">
              <template #prefix>
                <el-icon class="el-input__icon">
                  <Key/>
                </el-icon>
              </template>
            </el-input>
          </el-form-item>
          <div class="flex-center action">
            <el-checkbox v-model:model-value="rememberThePassword.remember">记住用户名</el-checkbox>
            <el-button @click="submit" :loading="loading" text bg icon="Right">
              {{ setupMode ? '创建账号' : '登录' }}
            </el-button>
          </div>
        </el-form>
      </div>
    </div>
    <div class="footer">
      <el-link type="default"
               href="https://docs.wushuo.top"
               target="_blank">
        ani-rss
      </el-link>
      &nbsp;
      <el-link type="default"
               href="https://github.com/wushuo894/ani-rss"
               target="_blank">
        github
      </el-link>
    </div>
  </div>
</template>

<script setup>
import {onMounted, ref} from "vue";
import * as http from "./js/http.js";
import {Key} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus";
import {authorization, initAuth, rememberThePassword} from "@/js/global.js";

let loading = ref(false)
let setupMode = ref(false)
let setupCode = ref('')

let user = ref({
  username: '',
  password: ''
})

/**
 * 登录
 */
let submit = () => {
  let {username, password} = user.value;

  if (!password || !username) {
    ElMessage.error('请输入账号与密码')
    return
  }
  if (setupMode.value && !setupCode.value) {
    ElMessage.error('请输入一次性设置码')
    return
  }

  loading.value = true
  const action = setupMode.value
      ? http.setup({code: setupCode.value, ...user.value})
      : http.login(user.value)
  action
      .then(res => {
        if (rememberThePassword.value.remember) {
          rememberThePassword.value.username = username
        } else {
          rememberThePassword.value.username = ''
        }
      })
      .finally(() => {
        loading.value = false
      })
}

/**
 * 测试是否处于白名单
 */
onMounted(async () => {
  await initAuth()
  if (!authorization.value) {
    http.setupStatus().then(res => setupMode.value = Boolean(res.data.required))
  }
  let {remember, username} = rememberThePassword.value;
  if (remember && username) {
    user.value.username = username
  }
})

</script>

<style scoped>
.content {
  width: 100%;
  height: 100%;
  flex-flow: column;
  justify-content: space-between;
}

#form {
  max-width: 200px;
}

.title-h2 {
  text-align: center;
  margin-bottom: 32px;
}

el-input {
  width: 200px;
}

.action {
  width: 100%;
  justify-content: space-between;
}

.footer {
  margin-bottom: 16px;
}

@media (max-width: 450px) {
  #form {
    width: 80%;
  }
}

#login-page {
  flex: 1;
  width: 100%;
}
</style>
