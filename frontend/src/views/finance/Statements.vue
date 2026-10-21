<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">对账单</div>
      <div class="muted">按客户选择尚未对账的发货记录生成对账单，也可按月份自动汇总。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="customerKeyword" placeholder="客户名称 / 联系人" clearable />
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="board-summary">
          <span class="table-count">客户档案共 {{ filteredCustomers.length }} 条</span>
          <div class="pending-summary" aria-live="polite">
            <span>待对账总金额<span v-if="customerKeyword.trim()" class="summary-scope">（全部客户）</span></span>
            <strong>{{ money(pendingTotal) }}<span class="summary-unit">元</span></strong>
          </div>
        </div>
        <el-button @click="loadCustomerBoard">刷新</el-button>
      </div>
      <el-table :data="pagedRows" height="calc(100vh - 324px)" :fit="false" border stripe>
        <el-table-column :index="rowIndex" type="index" label="#" width="50" fixed />
        <el-table-column prop="customerName" label="客户" width="150" show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="150" show-overflow-tooltip />
        <el-table-column prop="pendingDeliveryCount" label="待对账记录" width="150" />
        <el-table-column prop="pendingAmount" label="待对账金额" width="150" :formatter="moneyColumn" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openCustomer(row)">详情</el-button></template>
        </el-table-column>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="total" layout="total, prev, pager, next, jumper" /><span class="muted">每页 {{ pageSize }} 条</span></div>
    </div>

    <el-drawer v-model="customerDrawerVisible" title="客户对账详情" size="96%" class="customer-drawer">
      <div v-if="customerDetail.customer" class="detail-shell">
        <div class="summary-grid">
          <div><span>客户</span><strong>{{ customerDetail.customer.name }}</strong></div>
          <div><span>联系人</span><strong>{{ customerDetail.customer.contactPerson || '-' }}</strong></div>
          <div><span>待对账明细</span><strong>{{ customerDetail.deliveries?.length || 0 }}</strong></div>
          <div><span>历史对账单</span><strong>{{ customerDetail.statements?.length || 0 }}</strong></div>
        </div>

        <div class="drawer-toolbar">
          <div>
            <div class="section-title">待对账发货 / 退货记录</div>
            <div class="muted">每个发货型号单独一行，可分别勾选和修改整数数量。金额按该行数量 × 单价计算；退货为负数量，冲减合计。</div>
          </div>
          <div class="drawer-actions">
            <el-select v-if="onlyReturnsSelected" v-model="returnTargetStatement" placeholder="选择要冲减的已有对账单" style="width: 280px">
              <el-option v-for="statement in customerDetail.statements || []" :key="statement.id" :value="statement.id" :label="`${statement.statementNo}（${money(statement.receivableAmount)}元）`" />
            </el-select>
            <el-form-item v-else label="对账月份" required :error="!manualMonth ? '对账月份未填写' : undefined" class="month-field">
              <el-date-picker v-model="manualMonth" type="month" value-format="YYYY-MM" style="width: 160px" />
            </el-form-item>
            <el-button type="primary" :loading="generatingStatement" @click="generateSelected">{{ onlyReturnsSelected ? '加入对账单' : '生成对账单' }}</el-button>
          </div>
        </div>
        <el-table :data="customerDetail.deliveries || []" row-key="rowKey" height="360" border stripe :row-class-name="statementItemRowClass" @selection-change="selectedDeliveries = $event">
          <el-table-column type="selection" width="50" fixed="left" />
          <el-table-column prop="orderNo" label="订单号" width="160" />
          <el-table-column prop="deliveryNo" label="送货单号" width="160" />
          <el-table-column label="产品" min-width="220">
            <template #default="{ row }">
              <div v-for="product in row.products" :key="product.id" class="candidate-product" :title="product.name">{{ product.name || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="型号" min-width="170">
            <template #default="{ row }">
              <div v-for="product in row.products" :key="product.id" class="candidate-product" :title="product.spec">{{ product.spec || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="编号" min-width="150">
            <template #default="{ row }">
              <div v-for="product in row.products" :key="product.id" class="candidate-product" :title="product.code">{{ product.code || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="95">
            <template #default="{ row }">
              <div v-for="product in row.products" :key="product.id" class="candidate-product">{{ formatPrice(product.unitPrice) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="maxQty" label="剩余数量" width="110" fixed="right" />
          <el-table-column label="本次对账数量" width="160" fixed="right">
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty"
                :min="row.returnId ? Number(row.maxQty) : 1"
                :max="row.returnId ? -1 : Number(row.maxQty)"
                :precision="0"
                :step="1"
                step-strictly
                :controls="false"
                class="candidate-qty-input"
                :aria-label="`${row.deliveryNo} ${row.products?.[0]?.name || ''} ${row.products?.[0]?.spec || ''}本次对账数量`"
              />
            </template>
          </el-table-column>
          <el-table-column label="金额" width="130" fixed="right"><template #default="{ row }">{{ money(candidateAmount(row)) }}</template></el-table-column>
          <el-table-column prop="deliveryDate" label="发货日期" width="120" />
          <el-table-column prop="deliveryMethod" label="发货方式" width="130" />
        </el-table>
        <div class="selection-total">
          <span>已选 {{ selectedDeliveries.length }} 条</span>
          <strong>合计金额：{{ selectedAmount }}</strong>
        </div>

        <div class="section-title history-title">该客户对账单记录</div>
        <el-table :data="customerDetail.statements || []" max-height="260" border stripe>
          <el-table-column prop="statementNo" label="对账单号" width="160" />
          <el-table-column prop="month" label="月份" width="100" />
          <el-table-column prop="receivableAmount" label="金额" width="120" :formatter="moneyColumn" />
          <el-table-column prop="status" label="状态" width="140" />
          <el-table-column label="生成时间" min-width="180">
            <template #default="{ row }">{{ formatDate(row.generatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openStatementDetail(row)">详情</el-button>
              <el-button link @click="downloadStatement(row)">下载</el-button>
              <el-popconfirm title="确认删除该对账单？" @confirm="deleteStatement(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <el-dialog v-model="statementDetailVisible" title="对账单明细" width="min(1040px, 94vw)" destroy-on-close append-to-body>
      <div v-if="statementDetail.statement" class="detail-shell">
        <div class="summary-grid">
          <div><span>对账单号</span><strong>{{ statementDetail.statement.statementNo }}</strong></div>
          <div><span>客户</span><strong>{{ statementDetail.statement.customerName }}</strong></div>
          <div><span>对账月份</span><strong>{{ statementDetail.statement.month || '-' }}</strong></div>
          <div><span>账单金额</span><strong>{{ money(statementDetail.statement.receivableAmount) }}</strong></div>
        </div>
        <div class="detail-heading">
          <div>
            <div class="section-title">账单项目</div>
            <div class="muted">退货使用负数量，金额由数量与单价自动计算。</div>
          </div>
          <div class="detail-actions">
            <el-button @click="downloadStatement(statementDetail.statement)">下载 Excel</el-button>
            <el-button @click="openAmountEditor">修改金额</el-button>
            <el-button type="primary" @click="openAdjustment()">新增退货</el-button>
          </div>
        </div>
        <el-table :data="statementDetail.items || []" max-height="460" border stripe :row-class-name="statementItemRowClass">
          <el-table-column label="日期" width="110"><template #default="{ row }">{{ row.deliveryDate || '-' }}</template></el-table-column>
          <el-table-column prop="orderNo" label="单据号" width="150" show-overflow-tooltip />
          <el-table-column prop="deliveryNo" label="送货单号" width="150" />
          <el-table-column prop="productName" label="品名" min-width="150" show-overflow-tooltip />
          <el-table-column prop="spec" label="规格 / 型号" min-width="170" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column prop="qty" label="数量" width="100" />
          <el-table-column prop="unitPrice" label="单价" width="100" :formatter="priceColumn" />
          <el-table-column prop="amount" label="金额" width="120" :formatter="moneyColumn" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.adjustmentType === 'RETURN'" type="danger">退货</el-tag>
              <el-tag v-else-if="row.adjustmentType === 'AMOUNT'" type="warning">金额调整</el-tag>
              <span v-else>发货</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <template v-if="row.adjustmentType === 'RETURN'">
                <el-button v-if="!row.returnId" link type="primary" @click="openAdjustment(row)">修改</el-button>
                <el-popconfirm title="确认删除这条退货调整？" @confirm="deleteAdjustment(row)">
                  <template #reference><el-button link type="danger">删除</el-button></template>
                </el-popconfirm>
              </template>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="amountVisible" title="修改对账单金额" width="460px" destroy-on-close append-to-body>
      <el-form label-position="top">
        <el-form-item label="对账单金额" required :error="amountError">
          <el-input-number v-model="amountForm.receivableAmount" :min="0" :step="0.001" :controls="false" class="full-control" />
        </el-form-item>
        <div class="muted">修改后会同步重算剩余欠款、收款分配和导出总计。</div>
      </el-form>
      <template #footer>
        <el-button @click="amountVisible = false">取消</el-button>
        <el-button type="primary" :loading="amountSubmitting" @click="saveStatementAmount">保存金额</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="adjustmentVisible" :title="editingAdjustment ? '修改退货' : '新增退货'" width="min(680px, 94vw)" destroy-on-close append-to-body>
      <el-form label-position="top">
        <div class="adjustment-grid">
          <el-form-item label="退货日期" required :error="adjustmentErrors.adjustmentDate">
            <el-date-picker v-model="adjustmentForm.adjustmentDate" type="date" value-format="YYYY-MM-DD" class="full-control" />
          </el-form-item>
          <el-form-item label="单据号（选填）"><el-input v-model="adjustmentForm.documentNo" maxlength="64" placeholder="可留空" /></el-form-item>
          <el-form-item label="品名" required :error="adjustmentErrors.productName"><el-input v-model="adjustmentForm.productName" maxlength="200" /></el-form-item>
          <el-form-item label="规格 / 型号"><el-input v-model="adjustmentForm.spec" maxlength="200" /></el-form-item>
          <el-form-item label="单位"><el-input v-model="adjustmentForm.unit" maxlength="30" /></el-form-item>
          <el-form-item label="退货数量" required :error="adjustmentErrors.qty">
            <el-input-number v-model="adjustmentForm.qty" :min="-999999999" :max="-0.001" :precision="3" :controls="false" class="full-control" />
          </el-form-item>
          <el-form-item label="单价" required :error="adjustmentErrors.unitPrice">
            <el-input-number v-model="adjustmentForm.unitPrice" :min="0.00001" :controls="false" class="full-control" />
          </el-form-item>
          <el-form-item label="冲减金额"><el-input :model-value="money(adjustmentAmount)" readonly /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="adjustmentVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustmentSubmitting" @click="saveAdjustment">保存退货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useListPagination } from '@/composables/useListPagination'
import { formatMoney as money, formatPrice, moneyColumn, priceColumn, decimalMultiply, decimalSum } from '@/format/money'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { getAction, postAction, remove, update } from '@/api/crud'
import { usePageAutoRefresh } from '@/composables/usePageAutoRefresh'

const route = useRoute()
const initialKeyword = Array.isArray(route.query.keyword) ? route.query.keyword[0] : route.query.keyword
const customerKeyword = ref(typeof initialKeyword === 'string' ? initialKeyword : '')
const manualMonth = ref(currentMonthText())
const customers = ref<any[]>([])
const customerDetail = ref<any>({})
const selectedDeliveries = ref<any[]>([])
const generatingStatement = ref(false)
const customerDrawerVisible = ref(false)
const statementDetailVisible = ref(false)
const statementDetail = ref<any>({})
const adjustmentVisible = ref(false)
const amountVisible = ref(false)
const editingAdjustment = ref<any>()
const adjustmentSubmitting = ref(false)
const amountSubmitting = ref(false)
const adjustmentForm = reactive<any>({})
const amountForm = reactive<any>({ receivableAmount: undefined })
const adjustmentErrors = reactive<Record<string, string>>({})
const amountError = ref('')
const returnTargetStatement = ref<number>()
const onlyReturnsSelected = computed(() => selectedDeliveries.value.length > 0 && selectedDeliveries.value.every(item => item.returnId))

const filteredCustomers = computed(() => {
  const key = customerKeyword.value.trim().toLowerCase()
  return customers.value
    .filter(row => !key || [row.customerName, row.contactPerson].join(' ').toLowerCase().includes(key))
    .sort((a, b) => Number(b.pendingAmount || 0) - Number(a.pendingAmount || 0))
})
const pendingTotal = computed(() => decimalSum(customers.value.map(row => row.pendingAmount)))
const selectedAmount = computed(() => money(decimalSum(selectedDeliveries.value.map(candidateAmount))))
const adjustmentAmount = computed(() => decimalMultiply(adjustmentForm.qty, adjustmentForm.unitPrice))

async function loadCustomerBoard() {
  customers.value = await getAction('/finance/statements/customer-board')
}

async function openCustomer(row: any) {
  customerDetail.value = prepareCustomerDetail(await getAction(`/finance/statements/customers/${row.customerId}/candidates`))
  selectedDeliveries.value = []
  returnTargetStatement.value = undefined
  manualMonth.value = currentMonthText()
  customerDrawerVisible.value = true
}

async function generateSelected() {
  if (generatingStatement.value) return
  if (onlyReturnsSelected.value && !returnTargetStatement.value) return ElMessage.error('请选择要冲减的已有对账单，或同时勾选发货记录生成新单')
  if (!onlyReturnsSelected.value && !manualMonth.value) return ElMessage.error('对账月份未填写')
  if (!selectedDeliveries.value.length) return ElMessage.error('请先勾选发货记录')
  for (const item of selectedDeliveries.value) {
    const qty = Number(item.qty)
    const maxQty = Number(item.maxQty)
    if (!Number.isFinite(qty) || (item.returnId ? qty >= 0 : qty <= 0)) return ElMessage.error(item.returnId ? '退货对账数量必须小于0' : `“${item.deliveryNo}”的对账数量必须大于0`)
    if (!Number.isInteger(qty)) return ElMessage.error(`“${item.deliveryNo}”的对账数量必须为整数`)
    if (Math.abs(qty) > Math.abs(maxQty)) return ElMessage.error(`“${item.deliveryNo}”的对账数量不能超过剩余数量 ${Math.abs(maxQty)}`)
  }
  generatingStatement.value = true
  try {
    await postAction(onlyReturnsSelected.value ? `/finance/statements/${returnTargetStatement.value}/returns` : '/finance/statements/generate-selected', {
      month: manualMonth.value,
      deliverySelections: selectedDeliveries.value.map(item => item.returnId ? { returnId: item.returnId, qty: item.qty } : { deliveryId: item.id, deliveryItemId: item.deliveryItemId, qty: item.qty })
    })
    ElMessage.success(onlyReturnsSelected.value ? '退货已加入对账单' : '对账单已生成')
    await refreshCustomerContext()
  } finally {
    generatingStatement.value = false
  }
}

async function deleteStatement(row: any) {
  await remove('/finance/statements', row.id)
  ElMessage.success('对账单已删除')
  await refreshCustomerContext()
}

async function openStatementDetail(row: any) {
  statementDetail.value = await getAction(`/finance/statements/${row.id}/detail`)
  statementDetailVisible.value = true
}

function openAmountEditor() {
  amountError.value = ''
  amountForm.receivableAmount = Number(statementDetail.value.statement?.receivableAmount || 0)
  amountVisible.value = true
}

async function saveStatementAmount() {
  const amount = Number(amountForm.receivableAmount)
  amountError.value = Number.isFinite(amount) && amount > 0 ? '' : '对账单金额必须大于0'
  if (amountError.value) return ElMessage.error(amountError.value)
  amountSubmitting.value = true
  try {
    const statementId = statementDetail.value.statement.id
    await update(`/finance/statements/${statementId}`, 'amount', { receivableAmount: amount })
    amountVisible.value = false
    await refreshStatementContext(statementId)
    ElMessage.success('对账单金额已修改')
  } finally { amountSubmitting.value = false }
}

function openAdjustment(row?: any) {
  editingAdjustment.value = row || undefined
  clearObject(adjustmentErrors)
  resetObject(adjustmentForm, row ? {
    adjustmentDate: row.deliveryDate || today(),
    documentNo: row.orderNo || '',
    productName: row.productName || '',
    spec: row.spec || '',
    unit: row.unit || '',
    qty: Number(row.qty),
    unitPrice: Number(row.unitPrice)
  } : {
    adjustmentDate: today(),
    documentNo: '',
    productName: '',
    spec: '',
    unit: '',
    qty: -1,
    unitPrice: undefined
  })
  adjustmentVisible.value = true
}

async function saveAdjustment() {
  clearObject(adjustmentErrors)
  if (!adjustmentForm.adjustmentDate) adjustmentErrors.adjustmentDate = '退货日期未填写'
  if (!String(adjustmentForm.productName || '').trim()) adjustmentErrors.productName = '品名未填写'
  if (!Number.isFinite(Number(adjustmentForm.qty)) || Number(adjustmentForm.qty) >= 0) adjustmentErrors.qty = '退货数量必须为负数'
  if (!Number.isFinite(Number(adjustmentForm.unitPrice)) || Number(adjustmentForm.unitPrice) <= 0) adjustmentErrors.unitPrice = '单价必须大于0'
  const firstError = Object.values(adjustmentErrors)[0]
  if (firstError) return ElMessage.error(firstError)
  adjustmentSubmitting.value = true
  try {
    const statementId = statementDetail.value.statement.id
    if (editingAdjustment.value) {
      await update(`/finance/statements/${statementId}/adjustments`, editingAdjustment.value.id, adjustmentForm)
    } else {
      await postAction(`/finance/statements/${statementId}/adjustments`, adjustmentForm)
    }
    adjustmentVisible.value = false
    editingAdjustment.value = undefined
    await refreshStatementContext(statementId)
    ElMessage.success('退货调整已保存')
  } finally { adjustmentSubmitting.value = false }
}

async function deleteAdjustment(row: any) {
  const statementId = statementDetail.value.statement.id
  await remove(`/finance/statements/${statementId}/adjustments`, row.id)
  await refreshStatementContext(statementId)
  ElMessage.success('退货调整已删除')
}

async function refreshStatementContext(statementId: number) {
  const [detail] = await Promise.all([
    getAction<any>(`/finance/statements/${statementId}/detail`),
    refreshCustomerContext()
  ])
  statementDetail.value = detail
}

async function downloadStatement(row: any) {
  const blob = await http.get<Blob, Blob>(`/finance/statements/${row.id}/export`, { responseType: 'blob' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${row.statementNo || '对账单'}.xlsx`
  anchor.click()
  URL.revokeObjectURL(url)
}

function statementItemRowClass({ row }: any) { return row.adjustmentType === 'RETURN' ? 'return-item-row' : '' }
function prepareCustomerDetail(detail: any) {
  const deliveries = (detail?.deliveries || []).flatMap((row: any) => {
    if (row.returnId) {
      const maxQty = Number(row.maxQty ?? row.qty ?? 0)
      return [{ ...row, rowKey: `return-${row.returnId}`, maxQty, qty: maxQty }]
    }
    return (row.products || []).map((product: any) => {
      const maxQty = Number(product.remainingQty || 0)
      return {
        ...row, rowKey: `delivery-${row.id}-item-${product.id}`, deliveryItemId: product.id,
        orderNo: product.orderNo || row.orderNo, products: [product], maxQty, qty: maxQty,
        amount: product.amount, quantitySegments: [{ qty: maxQty, unitPrice: product.unitPrice }]
      }
    })
  })
  return { ...detail, deliveries }
}
function candidateAmount(row: any) {
  const qty = Number(row.qty)
  if (!Number.isFinite(qty) || (row.returnId ? qty >= 0 : qty <= 0)) return 0
  let remaining = Math.round(Math.abs(qty) * 1000)
  const amounts: string[] = []
  for (const segment of row.quantitySegments || []) {
    if (remaining <= 0) break
    const selectedQty = Math.min(remaining, Math.max(0, Math.round(Number(segment.qty || 0) * 1000)))
    amounts.push(decimalMultiply(String(selectedQty / 1000), segment.unitPrice))
    remaining -= selectedQty
  }
  return decimalMultiply(decimalSum(amounts), row.returnId ? -1 : 1)
}
function resetObject(target: Record<string, any>, value: Record<string, any>) { clearObject(target); Object.assign(target, value) }
function clearObject(target: Record<string, any>) { Object.keys(target).forEach(key => delete target[key]) }
function today() { const now = new Date(); return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10) }

function formatDate(value?: string) {
  if (!value) return '-'
  return value.slice(0, 10)
}

async function refreshAll() {
  await loadCustomerBoard()
}

async function refreshCustomerContext() {
  const customerId = customerDetail.value.customer?.id
  const [board, detail] = await Promise.all([
    getAction<any[]>('/finance/statements/customer-board'),
    customerId ? getAction<any>(`/finance/statements/customers/${customerId}/candidates`) : Promise.resolve(null)
  ])
  customers.value = board
  if (detail) customerDetail.value = prepareCustomerDetail(detail)
  selectedDeliveries.value = []
}

function currentMonthText() {
  const date = new Date()
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

const { currentPage, pageSize, total, pagedRows, rowIndex } = useListPagination(filteredCustomers, [customerKeyword])
onMounted(refreshAll)
usePageAutoRefresh(refreshAll, customerDrawerVisible)
</script>

<style scoped>
.board-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 24px; min-width: 0; }
.pending-summary { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 10px; font-size: 13px; color: var(--oms-sub); }
.pending-summary strong { color: var(--oms-primary); font-size: 20px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.summary-unit { margin-left: 4px; font-size: 13px; font-weight: 400; color: var(--oms-sub); }
.summary-scope { font-size: 12px; }

.detail-shell { display: flex; flex-direction: column; gap: 16px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.summary-grid > div { border: 1px solid var(--oms-border); border-radius: 8px; min-width: 0; padding: 12px; }
.summary-grid span { color: var(--oms-sub); display: block; font-size: 12px; margin-bottom: 6px; }
.summary-grid strong { color: var(--oms-heading); display: block; overflow-wrap: anywhere; }
.drawer-toolbar { align-items: flex-end; display: flex; justify-content: space-between; gap: 12px; }
.drawer-actions { align-items: center; display: flex; gap: 10px; }
.month-field { margin-bottom: 0; }
.candidate-qty-input { width: 132px; }
.candidate-product { line-height: 28px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.selection-total { align-items: center; border: 1px solid var(--oms-border); border-top: 0; display: flex; justify-content: flex-end; gap: 20px; min-height: 50px; padding: 0 18px; }
.selection-total span { color: var(--oms-sub); }
.selection-total strong { color: #dc2626; font-size: 18px; }
.history-title { margin-top: 6px; }
.detail-heading { align-items: flex-end; display: flex; justify-content: space-between; gap: 12px; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.adjustment-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.full-control { width: 100%; }
:deep(.return-item-row td.el-table__cell) { background: #fff7f7 !important; color: #dc2626; }
:deep(.return-item-row .el-button) { color: inherit; }
@media (max-width: 900px) {
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .drawer-toolbar { align-items: stretch; flex-direction: column; }
  .drawer-actions { flex-wrap: wrap; }
  .detail-heading { align-items: stretch; flex-direction: column; }
  :deep(.customer-drawer) { width: 96% !important; }
}
@media (max-width: 620px) {
  .summary-grid, .adjustment-grid { grid-template-columns: 1fr; }
  .drawer-actions { align-items: stretch; flex-direction: column; }
  .month-field, .month-field :deep(.el-date-editor) { width: 100% !important; }
  .selection-total { align-items: flex-end; flex-direction: column; gap: 4px; justify-content: center; padding-block: 10px; }
}
</style>
