<template>
  <div class="no-print" style="display:flex;gap:8px">
    <el-button @click="printPage">浏览器打印</el-button>
    <el-button type="primary" @click="exportHtml">导出 HTML</el-button>
  </div>
</template>

<script setup lang="ts">
function printPage() {
  window.print()
}

function exportHtml() {
  const html = document.documentElement.outerHTML
  const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `print-${Date.now()}.html`
  a.click()
  URL.revokeObjectURL(url)
}
</script>
