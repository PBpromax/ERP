<template>
  <CrudPage
    ref="pageRef"
    title="角色管理"
    description="维护系统角色。角色会与菜单、操作、字段权限联动。"
    endpoint="/system/roles"
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
    "prop": "code",
    "label": "角色编码"
  },
  {
    "prop": "name",
    "label": "角色名称"
  },
  {
    "prop": "description",
    "label": "说明"
  }
]
const fields = [
  {
    "prop": "code",
    "label": "角色编码"
  },
  {
    "prop": "name",
    "label": "角色名称",
    "required": true
  },
  {
    "prop": "description",
    "label": "说明",
    "type": "textarea",
    "span": 24
  }
]
</script>
