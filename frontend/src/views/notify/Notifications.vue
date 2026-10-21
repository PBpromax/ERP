<template>
  <CrudPage
    ref="pageRef"
    title="消息提醒"
    description="集中处理待审核、待发货、库存预警、原料不足、待开票和欠款提醒。"
    endpoint="/notify"
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
    "prop": "title",
    "label": "标题"
  },
  {
    "prop": "type",
    "label": "类型"
  },
  {
    "prop": "level",
    "label": "级别"
  },
  {
    "prop": "status",
    "label": "状态",
    "type": "status"
  },
  {
    "prop": "businessType",
    "label": "业务类型"
  },
  {
    "prop": "businessNo",
    "label": "业务单号"
  },
  {
    "prop": "createdAt",
    "label": "创建时间"
  }
]
const fields = [
  {
    "prop": "title",
    "label": "标题"
  },
  {
    "prop": "content",
    "label": "内容",
    "type": "textarea",
    "span": 24
  },
  {
    "prop": "type",
    "label": "类型"
  },
  {
    "prop": "level",
    "label": "级别"
  },
  {
    "prop": "status",
    "label": "状态"
  },
  {
    "prop": "businessType",
    "label": "业务类型"
  },
  {
    "prop": "businessNo",
    "label": "业务单号"
  }
]
</script>
