<template>
  <el-dialog v-model="dialogVisible" :before-close="closeDialog" align-center center width="560" title="删除订阅">
    <div v-if="aniList.length === 1">
      <el-text class="mx-1" size="large">是否删除 {{ aniList[0].title }} 第{{ aniList[0].season }}季?</el-text>
    </div>
    <div v-else>
      <el-text class="mx-1" size="large">是否删除共 {{ aniList.length }} 个订阅?</el-text>
    </div>
    <el-alert class="delete-warning" type="warning" show-icon :closable="false"
              title="已确认归属的下载任务和本地文件会立即删除，无法恢复"/>
    <div class="action">
      <el-button icon="Delete" :loading="okLoading" @click="deleteSubscription" text bg type="danger">
        删除
      </el-button>
      <el-button icon="Close" bg text @click="closeDialog()">取消</el-button>
    </div>
  </el-dialog>
</template>

<script setup>

import {getCurrentInstance, ref} from "vue";
import {deleteSubscriptions} from "@/js/http.js";
import {ElMessage} from "element-plus";

const dialogVisible = ref(false)

const aniList = ref([])

let okLoading = ref(false)
const deleteSubscription = async () => {
  okLoading.value = true
  try {
    const ids = aniList.value.map(it => it.id)
    const response = await deleteSubscriptions(ids)
    const result = response.data
    ElMessage.success(`已删除 ${result.deletedSubscriptions} 个订阅`)
    if (instance.vnode.props.onCallback) {
      emit('callback')
    } else {
      window.$reLoadList()
    }
    dialogVisible.value = false
  } finally {
    okLoading.value = false
  }
}

const closeDialog = async (done) => {
  dialogVisible.value = false
  if (typeof done === 'function') done()
}

const show = (anis) => {
  if (!anis.length) {
    ElMessage.error('未选择订阅')
    return
  }

  aniList.value = JSON.parse(JSON.stringify(anis))
  dialogVisible.value = true
}

defineExpose({
  show
})

const instance = getCurrentInstance()

const emit = defineEmits(['callback'])
</script>

<style scoped>
.action {
  width: 100%;
  display: flex;
  justify-content: end;
  margin-top: 8px;
}

.delete-warning {
  margin-top: 12px;
}

@media (max-width: 600px) {
  :deep(.el-dialog) {
    width: calc(100vw - 24px) !important;
  }
}
</style>

