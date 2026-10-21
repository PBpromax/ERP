<template>
  <CrudPage
    ref="pageRef"
    title="质检报告"
    description="维护检验日期、检验人、结论、批次号和附件追溯信息。"
    endpoint="/quality/reports"
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
    "prop": "reportNo",
    "label": "报告号"
  },
  {
    "prop": "businessType",
    "label": "关联类型"
  },
  {
    "prop": "businessNo",
    "label": "关联单号"
  },
  {
    "prop": "productName",
    "label": "产品"
  },
  {
    "prop": "batchNo",
    "label": "批次号"
  },
  {
    "prop": "inspectionDate",
    "label": "检验日期"
  },
  {
    "prop": "inspector",
    "label": "检验人"
  },
  {
    "prop": "conclusion",
    "label": "结论",
    "type": "status"
  }
]
const fields = [
  {
    "prop": "reportNo",
    "label": "报告号"
  },
  {
    "prop": "businessType",
    "label": "关联类型"
  },
  {
    "prop": "businessNo",
    "label": "关联单号"
  },
  {
    "prop": "orderNo",
    "label": "订单号"
  },
  {
    "prop": "productId",
    "label": "产品ID",
    "type": "number",
    "precision": 0
  },
  {
    "prop": "productName",
    "label": "产品",
    "required": true
  },
  {
    "prop": "batchNo",
    "label": "批次号"
  },
  {
    "prop": "inspectionDate",
    "label": "检验日期",
    "type": "date",
    "required": true
  },
  {
    "prop": "inspector",
    "label": "检验人",
    "required": true
  },
  {
    "prop": "conclusion",
    "label": "结论",
    "required": true
  },
  {
    "prop": "remark",
    "label": "备注",
    "type": "textarea",
    "span": 24
  }
]
</script>
