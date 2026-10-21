<template>
  <CrudPage
    ref="pageRef"
    title="原料库存"
    description="展示原材料库存和安全库存，生产缺料会自动触发采购提醒。"
    endpoint="/inventory/material-stocks"
    :columns="columns"
    :fields="fields"
    :readonly="true"
    :allow-create="true"
  >

  </CrudPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { list } from '@/api/crud'
import CrudPage from '@/components/CrudPage.vue'


const pageRef = ref()
const materials = ref<any[]>([])
const materialOptions = computed(() => materials.value.map(material => ({
  label: `${material.code || '-'} / ${material.name || '-'}`,
  value: material.id
})))
const columns = [
  {
    "prop": "materialCode",
    "label": "材料编码"
  },
  {
    "prop": "materialName",
    "label": "材料名称"
  },
  {
    "prop": "currentQty",
    "label": "当前库存"
  },
  {
    "prop": "lockedQty",
    "label": "锁定库存"
  },
  {
    "prop": "safetyStock",
    "label": "安全库存"
  },
  {
    "prop": "stockAmount",
    "label": "库存金额",
    "type": "money"
  }
]
const fields = computed(() => [
  {
    "prop": "materialId",
    "label": "材料",
    "type": "select",
    "required": true,
    "options": materialOptions.value,
    "span": 24
  },
  {
    "prop": "currentQty",
    "label": "当前库存",
    "type": "number",
    "precision": 3,
    "defaultValue": 0
  },
  {
    "prop": "lockedQty",
    "label": "锁定库存",
    "type": "number",
    "precision": 3,
    "defaultValue": 0
  },
  {
    "prop": "safetyStock",
    "label": "安全库存",
    "type": "number",
    "precision": 3
  },
  {
    "prop": "stockAmount",
    "label": "库存金额",
    "type": "money"
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
])

onMounted(async () => {
  materials.value = await list('/master/materials')
})
</script>
