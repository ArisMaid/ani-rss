<template>
  <input id="backup-file" hidden="hidden" type="file" @change="changeFile">
  <div class="content flex">
    <el-button bg @click="exportConfig" icon="Upload">导出设置</el-button>
    <el-button bg @click="importConfig" icon="Download">导入设置</el-button>
  </div>
</template>
<script setup>
import * as http from "@/js/http.js"
import {ElMessage, ElMessageBox} from "element-plus";

let importConfig = () => {
  document.querySelector('#backup-file').click()
}

let changeFile = async () => {
  let element = document.querySelector('#backup-file');
  const file = element.files[0]
  if (!file) return
  try {
    const staged = await http.stageRestore(file)
    if (staged.status !== 'VALIDATED') {
      ElMessage.error((staged.errors || []).join('; ') || '备份验证失败')
      return
    }
    const legacy = staged.legacy ? '旧格式备份；' : ''
    const warnings = (staged.warnings || []).map(value => `警告：${value}`)
    const files = (staged.files || []).slice(0, 10)
        .map(value => `${value.path} (${value.size} B)`)
    const omitted = staged.files.length > files.length
        ? `另有 ${staged.files.length - files.length} 个文件未在摘要中显示。`
        : ''
    const report = [
      `${legacy}已验证 ${staged.files.length} 个文件。`,
      ...warnings,
      ...files,
      omitted,
      '确认进入维护模式并恢复？'
    ].filter(Boolean).join('\n')
    await ElMessageBox.confirm(
        report,
        '确认恢复',
        {type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消'}
    )
    await http.confirmRestore(staged.operationId)
    const deadline = Date.now() + 120000
    while (Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500))
      const status = await http.restoreStatus(staged.operationId)
      if (status.status === 'SUCCEEDED') {
        ElMessage.success('恢复成功')
        setTimeout(() => location.reload(), 1000)
        return
      }
      if (['ROLLED_BACK', 'FAILED', 'MAINTENANCE_REQUIRED'].includes(status.status)) {
        ElMessage.error((status.errors || []).join('; ') || `恢复失败：${status.status}`)
        return
      }
    }
    ElMessage.error('恢复状态查询超时')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '恢复失败')
  } finally {
    element.value = ''
  }
}

let exportConfig = () => {
  let element = document.createElement('a');
  element.href = new URL('api/exportConfig', document.baseURI).toString()

  document.body.appendChild(element);

  element.click();

  document.body.removeChild(element);
}

let props = defineProps(['config'])
</script>
<style scoped>
.content {
  width: 100%;
  justify-content: center;
}
</style>
