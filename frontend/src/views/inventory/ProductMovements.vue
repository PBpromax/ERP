<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">出入库管理</div>
      <div class="muted">选择成品库存记录进行出库或入库，系统自动写入库存流水。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="产品 / 产品编号" clearable />
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="left-actions">
          <span class="table-count">选择库存记录后执行出库或入库</span>
        </div>
        <div class="right-actions">
          <span class="table-count">共 {{ filteredRows.length }} 条</span>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="pagedRows" height="calc(100vh - 278px)" border stripe>
        <el-table-column :index="rowIndex" type="index" width="50" label="#" fixed />
        <el-table-column prop="productName" label="产品" width="150" show-overflow-tooltip />
        <el-table-column prop="productCode" label="产品编号" width="150" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openMovement(row)">出/入库</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="total" layout="total, prev, pager, next, jumper" /><span class="muted">每页 {{ pageSize }} 条</span></div>

      <el-dialog v-model="dialogVisible" title="出/入库" width="520px">
        <el-form :model="form" label-width="100px">
          <el-form-item label="操作" required :error="movementErrors.movementType">
            <el-segmented v-model="form.movementType" :options="movementOptions" />
          </el-form-item>
          <el-form-item label="数量" required :error="movementErrors.qty"><el-input v-model="form.qty" /></el-form-item>
          <el-form-item label="操作人" required :error="movementErrors.operator"><el-input v-model="form.operator" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="saveMovement">保存</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useListPagination } from '@/composables/useListPagination'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAction, postAction } from '@/api/crud'

const rows = ref<any[]>([])
const keyword = ref('')
const selectedRow = ref<any>(null)
const dialogVisible = ref(false)
const form = reactive<any>({ movementType: 'OUT' })
const movementErrors = reactive<Record<string, string>>({})
const movementOptions = [
  { label: '出库', value: 'OUT' },
  { label: '入库', value: 'IN' }
]

const filteredRows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter(row => JSON.stringify(row).toLowerCase().includes(k))
})

async function load() {
  rows.value = await getAction('/inventory/product-stocks')
}

function openMovement(row: any) {
  selectedRow.value = row
  form.movementType = 'OUT'
  form.qty = ''
  form.operator = ''
  clearErrors()
  dialogVisible.value = true
}

async function saveMovement() {
  if (!selectedRow.value) return
  clearErrors()
  if (!form.movementType) movementErrors.movementType = '操作未填写'
  if (!Number.isFinite(Number(form.qty)) || Number(form.qty) <= 0) movementErrors.qty = '数量必须大于0'
  if (!String(form.operator || '').trim()) movementErrors.operator = '操作人未填写'
  const error = Object.values(movementErrors)[0]
  if (error) return ElMessage.error(error)
  await postAction(`/inventory/product-stocks/${selectedRow.value.id}/movement`, {
    movementType: form.movementType,
    qty: form.qty,
    operator: form.operator
  })
  ElMessage.success('操作完成')
  dialogVisible.value = false
  await load()
}

function clearErrors() { Object.keys(movementErrors).forEach(key => delete movementErrors[key]) }

const { currentPage, pageSize, total, pagedRows, rowIndex } = useListPagination(filteredRows, [keyword])
onMounted(load)
</script>

<style scoped></style>
