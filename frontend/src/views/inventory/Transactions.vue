<template>
  <CrudPage
    title="库存流水"
    description="审计出入库管理的操作记录，自动记录操作时间。"
    endpoint="/inventory/transactions"
    :columns="columns"
    :fields="[]"
    :readonly="true"
  />
</template>

<script setup lang="ts">
import CrudPage from '@/components/CrudPage.vue'

const columns = [
  { prop: 'itemName', label: '产品' },
  { prop: 'itemCode', label: '产品编号' },
  { prop: 'operator', label: '操作人' },
  { prop: 'txnType', label: '操作' },
  { prop: 'beforeQty', label: '操作前数量' },
  { prop: 'afterQty', label: '操作后数量' },
  { prop: 'createdAt', label: '操作时间', width: 180 }
]
</script>
