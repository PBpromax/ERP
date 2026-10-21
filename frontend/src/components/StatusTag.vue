<template>
  <el-tag :type="type" effect="light" round>{{ status || '-' }}</el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status?: string }>()
const type = computed(() => {
  const s = props.status || ''
  if (s === '未分配') return 'danger'
  if (s === '部分分配') return 'warning'
  if (s === '已分配') return 'success'
  if (s.includes('完成') || s.includes('已发货') || s.includes('已结清')) return 'success'
  if (s.includes('待') || s.includes('草稿')) return 'warning'
  if (s.includes('取消') || s.includes('不足') || s.includes('失效')) return 'danger'
  return 'info'
})
</script>
