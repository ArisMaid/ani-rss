<template>
  <el-dialog v-model="downloadPathDialogVisible" align-center center width="300"
             @close="onDownloadPathDialogClose" title="移动文件">
    <div>
      <strong>
        检测到修改后的下载位置发生了改动，是否将已下载文件移动到新的位置？
      </strong>
      <br>
      <el-text class="mx-1" size="small">
        {{ downloadPath }}
      </el-text>
    </div>
    <div class="action">
      <el-button icon="Check" text bg type="danger" @click="submitPathDecision(true)">移动
      </el-button>
      <el-button icon="Close" bg text @click="submitPathDecision(false)">不移动
      </el-button>
    </div>
  </el-dialog>
  <el-dialog v-model="dialogVisible" title="修改订阅" center v-if="dialogVisible">
    <Ani v-model:ani="ani" @callback="editChange"/>
  </el-dialog>
</template>

<script setup>

import {ref} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import Ani from "./Ani.vue";
import {aniData} from "@/js/ani.js";
import * as http from '@/js/http.js'


const dialogVisible = ref(false)
const downloadPathDialogVisible = ref(false)

const ani = ref(aniData)

let move = ref(false)
let downloadPath = ref('')
let callback = ref(() => {
})
let pathDecisionCommitted = ref(false)

const finish = () => {
  const done = callback.value
  callback.value = () => {
  }
  if (typeof done === 'function') done()
}

const editChange = async (fun) => {
  callback.value = typeof fun === 'function' ? fun : () => {
  }
  pathDecisionCommitted.value = false
  try {
    const req = await http.downloadPath(ani.value)
    downloadPath.value = req.data.downloadPath
    if (req.data.change) {
      downloadPathDialogVisible.value = true
      return
    }
    await editAni()
  } catch {
    finish()
  }
}

const submitPathDecision = shouldMove => {
  pathDecisionCommitted.value = true
  move.value = shouldMove
  downloadPathDialogVisible.value = false
  editAni()
}

const onDownloadPathDialogClose = () => {
  if (!pathDecisionCommitted.value) finish()
  pathDecisionCommitted.value = false
}

const editAni = () => {
  let action = () => http.setAni(move.value, ani.value)
      .then(res => {
        ElMessage.success(res.message)
        window.$reLoadList()
        dialogVisible.value = false
      })
      .finally(finish)

  if (!move.value) {
    return action()
  }

  return ElMessageBox.confirm(
      '将会移动整个文件夹, 是否执意继续?',
      '警告',
      {
        confirmButtonText: '执意继续移动',
        confirmButtonClass: 'is-text is-has-bg el-button--danger',
        cancelButtonText: '取消',
        cancelButtonClass: 'is-text is-has-bg',
        type: 'warning',
      }
  )
      .then(action)
      .catch(error => {
        finish()
        if (error !== 'cancel' && error !== 'close') throw error
      })
}

const show = (item) => {
  ani.value = JSON.parse(JSON.stringify(item))
  move.value = false
  pathDecisionCommitted.value = false
  dialogVisible.value = true
}

defineExpose({
  show
})
</script>

<style scoped>
.action {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 8px;
}
</style>
