<template>
  <CrudPage
    title="生产库存"
    description="从产品资料同步编号、名称和规格，维护工价和生产库存。"
    endpoint="/production/semi-finished-stocks"
    :columns="columns"
    :fields="fields"
  />
</template>

<script setup lang="ts">
import CrudPage from '@/components/CrudPage.vue'

const columns = [
  { prop: 'code', label: '编号', width: 150 },
  { prop: 'name', label: '名称', width: 150 },
  { prop: 'spec', label: '规格', width: 150 },
  { prop: 'laborPrice', label: '工价', type: 'money', width: 150 },
  { prop: 'stockQty', label: '库存', width: 150 }
]

const fields = [
  { prop: 'code', label: '编号' },
  { prop: 'name', label: '名称', required: true },
  { prop: 'spec', label: '规格' },
  { prop: 'laborPrice', label: '工价' },
  { prop: 'stockQty', label: '库存', defaultValue: 0 }
]
</script>
