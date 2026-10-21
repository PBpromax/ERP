<template>
  <CrudPage
    ref="pageRef"
    title="字段权限"
    description="控制单价、金额、成本等敏感字段是否可读；前端隐藏，后端脱敏。"
    endpoint="/system/field-permissions"
    :columns="columns"
    :fields="fields"

  >

  </CrudPage>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import CrudPage from '@/components/CrudPage.vue'


const pageRef = ref()
const columns = [
  {
    "prop": "roleCode",
    "label": "角色编码"
  },
  {
    "prop": "fieldCode",
    "label": "字段编码"
  },
  {
    "prop": "readable",
    "label": "可读"
  }
]
const fields = [
  {
    "prop": "roleCode",
    "label": "角色编码",
    "required": true
  },
  {
    "prop": "fieldCode",
    "label": "字段编码",
    "required": true
  },
  {
    "prop": "readable",
    "label": "可读",
    "type": "boolean"
  }
]
</script>
