<template>
  <CrudPage
    ref="pageRef"
    title="供应商"
    description="维护原料供应商和联系方式。"
    endpoint="/master/suppliers"
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
    "label": "供应商编码"
  },
  {
    "prop": "name",
    "label": "供应商名称"
  },
  {
    "prop": "contactPerson",
    "label": "联系人"
  },
  {
    "prop": "phone",
    "label": "电话"
  },
  {
    "prop": "address",
    "label": "地址"
  },
  {
    "prop": "enabled",
    "label": "启用"
  }
]
const fields = [
  {
    "prop": "code",
    "label": "供应商编码"
  },
  {
    "prop": "name",
    "label": "供应商名称",
    "required": true
  },
  {
    "prop": "contactPerson",
    "label": "联系人"
  },
  {
    "prop": "phone",
    "label": "电话"
  },
  {
    "prop": "address",
    "label": "地址",
    "span": 24
  },
  {
    "prop": "enabled",
    "label": "启用",
    "type": "boolean"
  }
]
</script>
