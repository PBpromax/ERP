<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">生产计划</div>
      <div class="muted">销售订单生成后自动出现生产计划，也支持手动新增和分配任务。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="订单号 / 产品 / 工人" clearable />
        </div>
        <div class="filter-field">
          <div class="filter-label">分配状态</div>
          <el-select v-model="statusFilter" clearable placeholder="全部" style="width: 160px">
            <el-option label="未分配" value="未分配" />
            <el-option label="部分分配" value="部分分配" />
            <el-option label="已分配" value="已分配" />
            <el-option label="已完工" value="已完工" />
          </el-select>
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="left-actions">
          <el-button type="primary" @click="openCreate">新增任务</el-button>
        </div>
        <div class="right-actions">
          <span class="table-count">共 {{ filteredPlans.length }} 条</span>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>

      <el-table :data="pagedPlans" height="calc(100vh - 270px)" border stripe>
        <el-table-column type="index" :index="rowIndex" width="50" label="#" fixed />
        <el-table-column prop="planNo" label="计划单号" width="150" />
        <el-table-column prop="sourceOrderNo" label="订单号" width="150" />
        <el-table-column prop="productCode" label="产品编号" width="150" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" width="150" show-overflow-tooltip />
        <el-table-column prop="productSpec" label="规格" width="150" show-overflow-tooltip />
        <el-table-column label="完成情况" width="150">
          <template #default="{ row }">{{ completionText(row) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="150">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column prop="expectedFinishDate" label="交付日期" width="150" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <div class="plan-actions">
              <el-button link type="primary" @click="openAssign(row)">分配任务</el-button>
              <el-button link type="success" @click="finish(row)">完工入库</el-button>
              <el-popconfirm title="确认删除该生产计划？" @confirm="deletePlan(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="PAGE_SIZE_OPTIONS" :total="filteredPlans.length" layout="total, sizes, prev, pager, next, jumper" />
      </div>
    </div>

    <el-dialog v-model="planDialogVisible" :title="planForm.id ? '编辑任务' : '新增任务'" width="760px">
      <el-form :model="planForm" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="计划单号"><el-input v-model="planForm.planNo" placeholder="保存后自动生成" disabled /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="产品编号">
              <el-autocomplete
                v-model="planForm.productCode"
                :fetch-suggestions="productCodeSuggestions"
                value-key="value"
                popper-class="product-suggestion-popper"
                clearable
                style="width: 100%"
                @select="selectProduct"
              >
                <template #default="{ item }">
                  <div class="product-suggestion-item">
                    <div class="product-suggestion-name">{{ item.value }}</div>
                    <div v-if="item.sub" class="product-suggestion-sub">{{ item.sub }}</div>
                  </div>
                </template>
              </el-autocomplete>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="产品名称" required :error="planErrors.product">
              <el-autocomplete
                v-model="planForm.productName"
                :fetch-suggestions="productNameSuggestions"
                value-key="value"
                popper-class="product-suggestion-popper"
                clearable
                style="width: 100%"
                @select="selectProduct"
              >
                <template #default="{ item }">
                  <div class="product-suggestion-item">
                    <div class="product-suggestion-name">{{ item.value }}</div>
                    <div v-if="item.sub" class="product-suggestion-sub">{{ item.sub }}</div>
                  </div>
                </template>
              </el-autocomplete>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="规格"><el-input v-model="planForm.productSpec" disabled /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="计划数量" required :error="planErrors.planQty"><el-input-number v-model="planForm.planQty" :precision="3" :min="0" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="交付日期">
              <el-date-picker v-model="planForm.expectedFinishDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input v-model="planForm.remark" type="textarea" :rows="3" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="planDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="savePlan">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignDialogVisible" title="分配任务" width="560px">
      <el-form :model="assignForm" label-width="100px">
        <el-form-item label="产品名称">
          <el-input v-model="assignForm.productName" disabled />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="assignForm.productSpec" disabled />
        </el-form-item>
        <el-form-item label="计划数量">
          <el-input v-model="assignForm.planQty" disabled />
        </el-form-item>
        <el-form-item label="已分配">
          <el-input :model-value="formatQty(assignForm.assignedQty)" disabled />
        </el-form-item>
        <el-form-item label="未分配">
          <el-input :model-value="formatQty(assignForm.unassignedQty)" disabled />
        </el-form-item>
        <el-form-item label="生产工人" required :error="assignErrors.workerId">
          <el-select v-model="assignForm.workerId" filterable placeholder="选择工人" style="width: 100%">
            <el-option v-for="worker in workers" :key="worker.id" :label="worker.name" :value="worker.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="生产数量" required :error="assignErrors.qty">
          <el-input-number v-model="assignForm.qty" :precision="0" :step="1" :min="0" style="width: 100%" />
        </el-form-item>
      </el-form>

      <section class="assignment-panel">
        <div class="assignment-panel-head">
          <div class="assignment-panel-title">已分配明细</div>
        </div>
        <div v-if="assignments.length" class="assignment-list">
          <div v-for="item in assignments" :key="item.id" class="assignment-row">
            <div class="assignment-row-head">
              <span>分配任务</span>
              <el-popconfirm
                title="删除后该工人的任务及相关生产分配明细会同步更新，确认删除？"
                width="360"
                @confirm="deleteAssignment(item)"
              >
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
            <div class="assignment-info">
              <div class="assignment-label">安排工人</div>
              <div class="assignment-value">{{ item.workerName || '-' }}</div>
            </div>
            <div class="assignment-info is-qty">
              <div class="assignment-label">安排件数</div>
              <div class="assignment-value">{{ formatQty(item.qty) }}</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无分配明细" />
      </section>

      <template #footer>
        <el-button @click="assignDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAssign">保存分配</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { create, list, postAction, remove } from '@/api/crud'
import http from '@/api/http'
import StatusTag from '@/components/StatusTag.vue'
import { compareProductCodeMatches, compareProductNameMatches, productCodeMatches, productCodeSuggestionSub, productNameMatches, productNameSuggestionSub } from '@/product/productSearch'

const plans = ref<any[]>([])
const workers = ref<any[]>([])
const productionStocks = ref<any[]>([])
const keyword = ref('')
const statusFilter = ref('')
const planDialogVisible = ref(false)
const assignDialogVisible = ref(false)
const planForm = reactive<any>({})
const assignForm = reactive<any>({})
const planErrors = reactive<Record<string, string>>({})
const assignErrors = reactive<Record<string, string>>({})
const selectedPlan = ref<any>(null)
const assignments = ref<any[]>([])
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)

const filteredPlans = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return plans.value.filter(plan => {
    const statusMatched = !statusFilter.value || plan.status === statusFilter.value
    const keywordMatched = !k || JSON.stringify(plan).toLowerCase().includes(k)
    return statusMatched && keywordMatched
  })
})
const pagedPlans = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredPlans.value.slice(start, start + pageSize.value)
})

function rowIndex(index: number) {
  return (currentPage.value - 1) * pageSize.value + index + 1
}

const stocksByCode = computed(() => [...productionStocks.value].sort((a, b) => String(a.code || '').localeCompare(String(b.code || ''), 'zh-Hans-CN')))
const stocksByName = computed(() => [...productionStocks.value].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN')))

async function load() {
  plans.value = await list('/production/plans')
  const lastPage = Math.max(1, Math.ceil(filteredPlans.value.length / pageSize.value))
  currentPage.value = Math.min(currentPage.value, lastPage)
}

async function loadSources() {
  const [workerRows, stockRows] = await Promise.all([
    list('/production/records'),
    list('/production/semi-finished-stocks')
  ])
  workers.value = workerRows
  productionStocks.value = stockRows
}

function openCreate() {
  Object.keys(planForm).forEach(key => delete planForm[key])
  clearErrors(planErrors)
  planForm.status = '未分配'
  planDialogVisible.value = true
}

async function savePlan() {
  clearErrors(planErrors)
  if (!planForm.productId && !String(planForm.productCode || '').trim() && !String(planForm.productName || '').trim()) planErrors.product = '产品未填写'
  if (!Number.isFinite(Number(planForm.planQty)) || Number(planForm.planQty) <= 0) planErrors.planQty = '计划数量必须大于0'
  const error = Object.values(planErrors)[0]
  if (error) return ElMessage.error(error)
  await create('/production/plans', planForm)
  ElMessage.success('任务已保存')
  planDialogVisible.value = false
  await load()
}

async function openAssign(row: any) {
  selectedPlan.value = row
  Object.keys(assignForm).forEach(key => delete assignForm[key])
  clearErrors(assignErrors)
  assignments.value = await getAssignments(row.id)
  const assignedQty = totalAssignedQty(assignments.value)
  const remainingQty = Math.max(0, Number(row.planQty || 0) - assignedQty)
  assignForm.productName = row.productName
  assignForm.productSpec = row.productSpec
  assignForm.planQty = row.planQty
  assignForm.assignedQty = assignedQty
  assignForm.unassignedQty = remainingQty
  assignForm.workerId = undefined
  assignForm.qty = Math.round(remainingQty)
  assignDialogVisible.value = true
}

async function saveAssign() {
  if (!selectedPlan.value?.id) return
  clearErrors(assignErrors)
  if (!assignForm.workerId) {
    assignErrors.workerId = '生产工人未填写'
    return ElMessage.error('生产工人未填写')
  }
  if (!Number(assignForm.qty) || Number(assignForm.qty) <= 0) {
    assignErrors.qty = '生产数量未填写'
    return ElMessage.error('生产数量未填写')
  }
  await postAction(`/production/plans/${selectedPlan.value.id}/assign`, {
    workerId: assignForm.workerId,
    qty: assignForm.qty
  })
  ElMessage.success('任务已分配')
  assignments.value = await getAssignments(selectedPlan.value.id)
  const planQty = Number(selectedPlan.value.planQty || 0)
  const assignedQty = totalAssignedQty(assignments.value)
  assignForm.assignedQty = assignedQty
  assignForm.unassignedQty = Math.max(0, planQty - assignedQty)
  assignForm.workerId = undefined
  assignForm.qty = Math.round(assignForm.unassignedQty)
  await load()
  if (assignForm.unassignedQty <= 0) assignDialogVisible.value = false
}

function clearErrors(errors: Record<string, string>) { Object.keys(errors).forEach(key => delete errors[key]) }

async function deleteAssignment(item: any) {
  if (!selectedPlan.value?.id || !item?.id) return
  await http.delete(`/production/plans/assignments/${item.id}`)
  ElMessage.success('分配任务已删除')
  assignments.value = await getAssignments(selectedPlan.value.id)
  const planQty = Number(selectedPlan.value.planQty || 0)
  const assignedQty = totalAssignedQty(assignments.value)
  assignForm.assignedQty = assignedQty
  assignForm.unassignedQty = Math.max(0, planQty - assignedQty)
  assignForm.qty = Math.round(assignForm.unassignedQty)
  await load()
}

async function finish(row: any) {
  await postAction(`/production/plans/${row.id}/finish`)
  ElMessage.success('完工入库成功')
  await load()
}

async function deletePlan(row: any) {
  await remove('/production/plans', row.id)
  ElMessage.success('生产计划已删除')
  await load()
}

function productCodeSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim().toLowerCase()
  if (!key) return cb([])
  cb(stocksByCode.value
    .filter(stock => productCodeMatches(stock, key))
    .sort((a, b) => compareProductCodeMatches(a, b, key))
    .map(stock => ({ value: stock.code, sub: productCodeSuggestionSub(stock), raw: stock })))
}

function productNameSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim().toLowerCase()
  if (!key) return cb([])
  cb(stocksByName.value
    .filter(stock => productNameMatches(stock, key))
    .sort((a, b) => compareProductNameMatches(a, b, key))
    .map(stock => ({ value: stock.name, sub: productNameSuggestionSub(stock, key), raw: stock })))
}

function selectProduct(item: any) {
  const stock = item.raw || {}
  delete planForm.productId
  planForm.productCode = stock.code
  planForm.productName = stock.name
  planForm.productSpec = stock.spec
}

async function getAssignments(planId: number | string) {
  return await http.get<any[], any[]>(`/production/plans/${planId}/assignments`)
}

function totalAssignedQty(rows: any[]) {
  return rows.reduce((sum, item) => sum + Number(item.qty || 0), 0)
}

function completionText(row: any) {
  return `${formatQty(row.completedQty || 0)}/${formatQty(row.planQty || 0)}`
}

function formatQty(value: any) {
  const number = Number(value || 0)
  if (!Number.isFinite(number)) return '0'
  return Number.isInteger(number) ? String(number) : String(number).replace(/\.?0+$/, '')
}

onMounted(async () => {
  await Promise.all([load(), loadSources()])
})
watch([keyword, statusFilter], () => { currentPage.value = 1 })
watch(pageSize, () => { currentPage.value = 1 })
</script>

<style scoped>
.plan-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0 10px;
}

.plan-actions :deep(.el-button) {
  margin-left: 0;
  padding: 0;
}

.assignment-panel {
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  margin-top: 18px;
  overflow: hidden;
}

.assignment-panel-head {
  align-items: center;
  border-bottom: 1px solid var(--oms-border);
  display: flex;
  justify-content: space-between;
  min-height: 56px;
  padding: 0 18px;
}

.assignment-panel-title {
  color: var(--oms-heading);
  font-size: 16px;
  font-weight: 760;
}

.assignment-list {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-height: 180px;
  overflow-y: auto;
  padding: 12px;
}

.assignment-row {
  background: #f8fafc;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  padding: 10px 14px;
}

.assignment-row-head {
  align-items: center;
  border-bottom: 1px solid var(--oms-border);
  color: var(--oms-sub);
  display: flex;
  font-size: 12px;
  justify-content: space-between;
  margin-bottom: 2px;
  padding-bottom: 4px;
}

.assignment-row-head :deep(.el-button) {
  margin-left: 0;
  padding: 0;
}

.assignment-info {
  align-items: center;
  display: flex;
  justify-content: space-between;
  min-width: 0;
}

.assignment-info.is-qty {
  text-align: left;
}

.assignment-label {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
}

.assignment-value {
  color: var(--oms-heading);
  font-size: 16px;
  font-weight: 760;
  line-height: 24px;
}

@media (max-width: 640px) {
  .assignment-list {
    grid-template-columns: 1fr;
  }
}
</style>
