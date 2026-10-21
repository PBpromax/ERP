<template>
  <CrudPage
    ref="pageRef"
    title="操作日志"
    description="记录新增、修改、删除、审核、导出、打印、登录失败等关键动作。"
    endpoint="/system/audit-logs"
    :columns="columns"
    :fields="fields"
    :readonly="true"
  >

  </CrudPage>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import CrudPage from '@/components/CrudPage.vue'


const pageRef = ref()
const columns = [
  {
    "prop": "username",
    "label": "用户"
  },
  {
    "prop": "module",
    "label": "模块"
  },
  {
    "prop": "action",
    "label": "动作"
  },
  {
    "prop": "businessKey",
    "label": "业务标识"
  },
  {
    "prop": "detail",
    "label": "详情"
  },
  {
    "prop": "ip",
    "label": "IP"
  },
  {
    "prop": "createdAt",
    "label": "时间"
  }
]
const fields = [
  {
    "prop": "username",
    "label": "用户"
  },
  {
    "prop": "module",
    "label": "模块"
  },
  {
    "prop": "action",
    "label": "动作"
  },
  {
    "prop": "businessKey",
    "label": "业务标识"
  },
  {
    "prop": "detail",
    "label": "详情",
    "type": "textarea",
    "span": 24
  },
  {
    "prop": "ip",
    "label": "IP"
  }
]
</script>
