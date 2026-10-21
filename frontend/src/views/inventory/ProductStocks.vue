<template>
  <CrudPage
    ref="pageRef"
    title="成品库存"
    description="维护成品库存台账，库存数量会随出入库管理自动更新。"
    endpoint="/inventory/product-stocks"
    :columns="columns"
    :fields="fields"
    :readonly="true"
    :allow-create="true"
  >
    <template #rowActions="{ row, reload }">
      <el-popconfirm title="确认删除该库存记录？" @confirm="deleteStock(row, reload)">
        <template #reference><el-button link type="danger">删除</el-button></template>
      </el-popconfirm>
    </template>
  </CrudPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { list, remove } from '@/api/crud'
import { ElMessage } from 'element-plus'
import CrudPage from '@/components/CrudPage.vue'

const pageRef = ref()
const products = ref<any[]>([])

const fields = computed(() => [
  {
    prop: 'productName',
    label: '产品',
    required: true,
    type: 'autocomplete',
    suggestionEndpoint: '/master/products',
    valueProp: 'name',
    codeProp: 'code',
    codeTargetProp: 'productCode',
    idTargetProp: 'productId',
    span: 24
  },
  {
    prop: 'productCode',
    label: '产品编号'
  },
  {
    prop: 'currentQty',
    label: '当前库存',
    defaultValue: 0
  }
])

const columns = [
  { prop: 'productName', label: '产品' },
  { prop: 'productCode', label: '产品编号' },
  { prop: 'currentQty', label: '当前库存' }
]

onMounted(async () => {
  products.value = await list('/master/products')
})

async function deleteStock(row: any, reload: Function) {
  await remove('/inventory/product-stocks', row.id)
  ElMessage.success('库存记录已删除')
  await reload()
}
</script>
