<template>
  <CrudPage
    ref="pageRef"
    title="采购申请"
    description="原料库存低于安全库存或生产缺料时，可生成采购申请并跟踪状态。"
    endpoint="/purchase/requests"
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
    "prop": "requestNo",
    "label": "申请单号"
  },
  {
    "prop": "materialName",
    "label": "材料"
  },
  {
    "prop": "requestQty",
    "label": "申请数量"
  },
  {
    "prop": "defaultSupplier",
    "label": "供应商"
  },
  {
    "prop": "expectedArrivalDate",
    "label": "期望到货"
  },
  {
    "prop": "status",
    "label": "状态",
    "type": "status"
  },
  {
    "prop": "sourceNo",
    "label": "来源单号"
  }
]
const fields = [
  {
    "prop": "requestNo",
    "label": "申请单号"
  },
  {
    "prop": "materialId",
    "label": "材料ID",
    "type": "number",
    "precision": 0
  },
  {
    "prop": "materialName",
    "label": "材料名称",
    "required": true
  },
  {
    "prop": "requestQty",
    "label": "申请数量",
    "required": true,
    "type": "number",
    "precision": 3
  },
  {
    "prop": "defaultSupplier",
    "label": "供应商"
  },
  {
    "prop": "expectedArrivalDate",
    "label": "期望到货",
    "type": "date"
  },
  {
    "prop": "status",
    "label": "状态"
  },
  {
    "prop": "sourceType",
    "label": "来源类型"
  },
  {
    "prop": "sourceNo",
    "label": "来源单号"
  },
  {
    "prop": "remark",
    "label": "备注",
    "type": "textarea",
    "span": 24
  }
]
</script>
