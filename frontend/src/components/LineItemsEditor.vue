<template>
  <el-table :data="modelValue" border>
    <el-table-column label="产品ID" width="120">
      <template #default="{ row }"><el-input-number v-model="row.productId" :min="1" /></template>
    </el-table-column>
    <el-table-column label="产品名称">
      <template #default="{ row }"><el-input v-model="row.productName" /></template>
    </el-table-column>
    <el-table-column label="规格">
      <template #default="{ row }"><el-input v-model="row.spec" /></template>
    </el-table-column>
    <el-table-column label="单位" width="100">
      <template #default="{ row }"><el-input v-model="row.unit" /></template>
    </el-table-column>
    <el-table-column label="数量" width="130">
      <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" /></template>
    </el-table-column>
    <el-table-column label="单价" width="150">
      <template #default="{ row }"><el-input-number v-model="row.unitPrice" :min="0" :step="0.001" /></template>
    </el-table-column>
    <el-table-column label="操作" width="90">
      <template #default="{ $index }"><el-button link type="danger" @click="removeLine($index)">删除</el-button></template>
    </el-table-column>
  </el-table>
  <el-button style="margin-top: 10px" @click="addLine">新增明细行</el-button>
</template>

<script setup lang="ts">
const props = defineProps<{ modelValue: any[] }>()
const emit = defineEmits(['update:modelValue'])

function addLine() {
  emit('update:modelValue', [...props.modelValue, { qty: 1, unitPrice: 0, shippedQty: 0 }])
}

function removeLine(index: number) {
  const rows = [...props.modelValue]
  rows.splice(index, 1)
  emit('update:modelValue', rows)
}
</script>
