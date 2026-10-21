<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">收款登记</div>
      <div class="muted">按公司统一查看每次对账、到账和欠款情况。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="客户名称 / 联系人" clearable />
        </div>
        <div class="filter-field">
          <div class="filter-label">欠款状态</div>
          <el-select v-model="debtFilter" placeholder="全部公司" clearable>
            <el-option label="存在欠款" value="debt" />
            <el-option label="已结清" value="clear" />
          </el-select>
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <span class="table-count">共 {{ filteredCompanies.length }} 家公司</span>
        <el-button @click="loadCompanies">刷新</el-button>
      </div>
      <el-table :data="pagedRows" height="calc(100vh - 278px)" :fit="false" border stripe>
        <el-table-column :index="rowIndex" type="index" label="#" width="50" fixed />
        <el-table-column prop="customerName" label="客户公司" min-width="240" fixed show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="130" show-overflow-tooltip />
        <el-table-column prop="totalSalesAmount" label="销售总额" width="150">
          <template #header>
            <el-tooltip content="今年有效销售订单的金额合计，与详情页目前总营业额一致，不含往年历史对账单。" placement="top">
              <span>销售总额</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">{{ row.totalSalesAmount == null ? '—' : money(row.totalSalesAmount) }}</template>
        </el-table-column>
        <el-table-column prop="statementCount" label="对账单" width="100" />
        <el-table-column prop="receivableAmount" label="账单金额" width="140" :formatter="moneyColumn" />
        <el-table-column prop="invoicedAmount" label="已开票" width="130" :formatter="moneyColumn" />
        <el-table-column prop="receivedAmount" label="已收款" width="130" :formatter="moneyColumn" />
        <el-table-column prop="arrearsAmount" label="剩余欠款" width="140" :formatter="moneyColumn" />
        <el-table-column prop="latestPaymentDate" label="最近付款日期" width="155">
          <template #default="{ row }">
            <div class="latest-payment-cell">
              <span>{{ row.latestPaymentDate || (row.hasUndatedPayments ? '日期待核实' : '暂无付款记录') }}</span>
              <el-tag size="small" :type="row.paidThisMonth ? 'success' : 'info'">
                {{ row.paidThisMonth ? '本月已付款' : row.hasUndatedPayments ? '本月待核实' : '本月未付款' }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="creditBalance" label="预收余额" width="130" :formatter="moneyColumn" />
        <el-table-column label="收款状态" width="120">
          <template #default="{ row }">
            <el-tag :type="Number(row.arrearsAmount || 0) > 0 ? 'warning' : 'success'">
              {{ Number(row.arrearsAmount || 0) > 0 ? '存在欠款' : Number(row.arrearsAmount || 0) < 0 ? '有预收款' : '已结清' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openCompany(row)">详情</el-button></template>
        </el-table-column>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="total" layout="total, prev, pager, next, jumper" /><span class="muted">每页 {{ pageSize }} 条</span></div>
    </div>

    <el-drawer v-model="companyVisible" title="公司收款详情" size="84%">
      <div v-if="detail.customer" class="detail-shell">
        <div class="detail-title-row">
          <div>
            <div class="customer-title">{{ detail.customer.name }}</div>
            <div class="muted">{{ detail.customer.contactPerson || '-' }} · {{ detail.customer.phone || '-' }}</div>
          </div>
          <el-button
            type="primary"
            @click="openCompanyPayment()"
          >登记到账</el-button>
        </div>

        <div class="summary-grid">
          <div><span>目前总营业额</span><strong>{{ detail.summary?.totalSalesAmount == null ? '—' : money(detail.summary.totalSalesAmount) }}</strong></div>
          <div><span>历史及当前账款</span><strong>{{ money(detail.summary?.statementAmount) }}</strong></div>
          <div><span>剩余欠款（负数为预收）</span><strong class="debt-text">{{ money(detail.summary?.currentBalance) }}</strong></div>
          <div><span>对账记录</span><strong>{{ detail.summary?.statementCount || 0 }}</strong></div>
          <div><span>未结清项目</span><strong>{{ detail.summary?.unpaidStatementCount || 0 }}</strong></div>
        </div>

        <div class="section-title">收款及账款调减记录</div>
        <el-table :data="detail.payments || []" max-height="240" border>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="allocation-detail">
                <div>未分配预收款：{{ money(row.unallocatedAmount) }}</div>
                <el-table :data="row.allocations || []" size="small" border>
                  <el-table-column prop="statementNo" label="分配对账单" />
                  <el-table-column prop="amount" label="分配金额" :formatter="moneyColumn" />
                  <el-table-column prop="allocationDate" label="抵扣日期" />
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="recordNo" label="流水记录" width="210" fixed show-overflow-tooltip />
          <el-table-column label="业务类型" width="110"><template #default="{ row }">{{ row.recordType === 'ADJUSTMENT' ? '账款调减' : '收款' }}</template></el-table-column>
          <el-table-column label="业务日期" width="150"><template #default="{ row }">{{ row.sourceDateEstimated ? `日期待核实（原表：${row.sourceDateText || '空白'}）` : row.paymentDate || `待确认（原表：${row.sourceDateText || '空白'}）` }}</template></el-table-column>
          <el-table-column prop="amount" label="金额" width="125" :formatter="moneyColumn" />
          <el-table-column prop="method" label="收款/调减方式" width="120" />
          <el-table-column prop="unallocatedAmount" label="预收余额" width="125" :formatter="moneyColumn" />
          <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <template v-if="!row.readOnly">
                <el-button link type="primary" @click="openCompanyPayment(row)">修改</el-button>
                <el-popconfirm title="确认删除这条到账记录并恢复欠款？" @confirm="deletePayment(row)">
                  <template #reference><el-button link type="danger">删除</el-button></template>
                </el-popconfirm>
              </template>
              <span v-else class="muted">只读</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="section-heading">
          <div class="section-title">对账记录</div>
          <div class="statement-filters">
            <el-segmented v-model="statementFilter" :options="statementOptions" />
            <el-segmented v-if="statementFilter === 'unpaid'" v-model="invoiceFilter" :options="invoiceOptions" />
          </div>
        </div>
        <el-table :data="filteredStatements" height="360" border :row-class-name="statementRowClass">
          <el-table-column prop="statementNo" label="对账单号" width="180" fixed show-overflow-tooltip />
          <el-table-column label="日期" width="150"><template #default="{ row }">{{ row.generatedAt ? formatDate(row.generatedAt) : `待确认（原表：${row.sourceDateText || '空白'}）` }}</template></el-table-column>
          <el-table-column prop="receivableAmount" label="账单金额" width="125" :formatter="moneyColumn" />
          <el-table-column prop="invoicedAmount" label="已开票" width="115" :formatter="moneyColumn" />
          <el-table-column prop="receivedAmount" label="已收款" width="115" :formatter="moneyColumn" />
          <el-table-column prop="arrearsAmount" label="剩余欠款" width="125" :formatter="moneyColumn" />
          <el-table-column label="结算方式" width="105"><template #default="{ row }">{{ methodText(row.settlementMethod) }}</template></el-table-column>
          <el-table-column label="状态" width="140"><template #default="{ row }"><el-tag :type="statusType(row.settlementStatus)">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button v-if="!row.pendingLedger && Number(row.arrearsAmount) > 0" link type="primary" @click="openStatementPayment(row)">还款</el-button>
              <span v-if="row.pendingLedger" class="muted">待核对</span>
              <template v-else>
              <el-button v-if="row.settlementStatus === 'UNREGISTERED'" link type="primary" @click="openSettlement(row)">登记</el-button>
              <el-button v-else link @click="openStatementDetail(row)">详情</el-button>
              <el-popconfirm title="确认删除该对账单？已有发票或收款记录的对账单无法删除。" @confirm="deleteStatement(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
              </template>
            </template>
          </el-table-column>
        </el-table>

      </div>
    </el-drawer>

    <el-dialog v-model="statementVisible" title="对账单收款详情" width="760px" destroy-on-close>
      <div v-if="statementDetail.statement" class="detail-shell compact-detail">
        <div class="detail-dialog-actions">
          <el-button @click="openAmountEditor">修改金额</el-button>
          <el-button v-if="Number(statementDetail.statement.arrearsAmount) > 0" type="primary" @click="openStatementPayment(statementDetail.statement)">还款</el-button>
        </div>
        <div class="summary-grid summary-grid-two">
          <div><span>对账单号</span><strong>{{ statementDetail.statement.statementNo }}</strong></div>
          <div><span>状态</span><strong>{{ statementDetail.statement.status }}</strong></div>
        </div>
        <div class="section-title">发票记录</div>
        <el-table :data="statementDetail.invoices || []" max-height="180" border>
          <el-table-column prop="invoiceNo" label="发票号" min-width="200" />
          <el-table-column prop="invoiceDate" label="开票日期" width="120" />
          <el-table-column prop="amount" label="金额" width="130" :formatter="moneyColumn" />
        </el-table>
        <div class="section-title">收款记录</div>
        <el-table :data="statementDetail.receipts || []" max-height="180" border>
          <el-table-column label="收款单号" min-width="180"><template #default="{ row }">{{ row.paymentNo || row.receiptNo }}</template></el-table-column>
          <el-table-column prop="receiptDate" label="到账日期" width="120" />
          <el-table-column prop="amount" label="金额" width="130" :formatter="moneyColumn" />
          <el-table-column prop="method" label="方式" width="120" />
        </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="amountVisible" title="修改对账单金额" width="460px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="对账单金额" required :error="amountError">
          <el-input-number v-model="amountForm.receivableAmount" :min="0" :step="0.001" :controls="false" class="full-control" />
        </el-form-item>
        <div class="muted">修改后会同步重算剩余欠款、FIFO收款分配和导出总计。</div>
      </el-form>
      <template #footer>
        <el-button @click="amountVisible = false">取消</el-button>
        <el-button type="primary" :loading="amountSubmitting" @click="saveStatementAmount">保存金额</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="companyPaymentVisible" :title="editingPayment ? '修改到账记录' : '到账登记'" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-alert title="收款按对账单先后顺序抵扣：先还最早的，结清后抵扣下一张；多余金额保留为预收款。" type="info" :closable="false" />
        <div class="form-grid">
          <el-form-item label="到账金额" required :error="companyErrors.amount"><el-input-number v-model="companyForm.amount" :min="0" :step="0.001" :controls="false" class="full-control" /></el-form-item>
          <el-form-item label="到账日期" required :error="companyErrors.paymentDate"><el-date-picker v-model="companyForm.paymentDate" type="date" value-format="YYYY-MM-DD" class="full-control" /></el-form-item>
          <el-form-item label="收款方式"><el-select v-model="companyForm.method" class="full-control"><el-option label="银行转账" value="银行转账" /><el-option label="开票回款" value="开票回款" /><el-option label="现汇" value="现汇" /><el-option label="客户汇款" value="客户汇款" /><el-option label="现金" value="现金" /><el-option label="承兑" value="承兑" /><el-option label="其他" value="其他" /></el-select></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="companyForm.remark" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="companyPaymentVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveCompanyPayment">{{ editingPayment ? '保存修改' : '确认到账' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="settlementVisible" title="登记结算方式" width="600px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="结算方式" required><el-segmented v-model="settlementForm.method" :options="methodOptions" block /></el-form-item>
        <template v-if="settlementForm.method === 'INVOICE'">
          <el-form-item label="发票号" required :error="settlementErrors.invoiceNo"><el-input v-model="settlementForm.invoiceNo" maxlength="64" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="开票金额" required :error="settlementErrors.amount"><el-input-number v-model="settlementForm.amount" :min="0" :max="selectedStatement?.receivableAmount" :step="0.001" :controls="false" class="full-control" /></el-form-item>
            <el-form-item label="开票日期" required :error="settlementErrors.transactionDate"><el-date-picker v-model="settlementForm.transactionDate" type="date" value-format="YYYY-MM-DD" class="full-control" /></el-form-item>
          </div>
        </template>
        <el-form-item label="备注"><el-input v-model="settlementForm.remark" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="settlementVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveSettlement">保存登记</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useListPagination } from '@/composables/useListPagination'
import { formatMoney as money, moneyColumn } from '@/format/money'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAction, postAction, remove, update } from '@/api/crud'
import { usePageAutoRefresh } from '@/composables/usePageAutoRefresh'

const companies = ref<any[]>([])
const keyword = ref('')
const debtFilter = ref('')
const companyVisible = ref(false)
const statementVisible = ref(false)
const amountVisible = ref(false)
const companyPaymentVisible = ref(false)
const settlementVisible = ref(false)
const submitting = ref(false)
const amountSubmitting = ref(false)
const detail = ref<any>({})
const statementDetail = ref<any>({})
const selectedStatement = ref<any>()
const statementFilter = ref('all')
const invoiceFilter = ref('all')
const companyForm = reactive<any>({})
const editingPayment = ref<any>()
const settlementForm = reactive<any>({})
const amountForm = reactive<any>({ receivableAmount: undefined })
const companyErrors = reactive<Record<string, string>>({})
const settlementErrors = reactive<Record<string, string>>({})
const amountError = ref('')
const statementOptions = [{ label: '全部', value: 'all' }, { label: '未付清', value: 'unpaid' }, { label: '已付清', value: 'paid' }]
const invoiceOptions = [{ label: '全部', value: 'all' }, { label: '已开票', value: 'invoiced' }, { label: '未开票', value: 'uninvoiced' }]
const methodOptions = [{ label: '现汇', value: 'CASH' }, { label: '开票', value: 'INVOICE' }]

const filteredCompanies = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return companies.value.filter(row => {
    const hasDebt = Number(row.arrearsAmount || 0) > 0
    if (debtFilter.value === 'debt' && !hasDebt) return false
    if (debtFilter.value === 'clear' && hasDebt) return false
    return !key || [row.customerName, row.contactPerson].join(' ').toLowerCase().includes(key)
  })
})
const filteredStatements = computed(() => [...(detail.value.statements || []), ...(detail.value.ledgerEntries || []).map((r: any) => ({ ...r, pendingLedger: true }))].filter((row: any) => {
  if (statementFilter.value === 'unpaid' && row.settlementStatus === 'PAID') return false
  if (statementFilter.value === 'paid') return row.settlementStatus === 'PAID'
  if (statementFilter.value === 'unpaid' && invoiceFilter.value === 'invoiced' && row.invoiceStatus !== 'INVOICED') return false
  if (statementFilter.value === 'unpaid' && invoiceFilter.value === 'uninvoiced' && row.invoiceStatus !== 'UNINVOICED') return false
  return true
}))

async function loadCompanies() { companies.value = await getAction('/finance/collections/companies') }
async function openCompany(row: any) {
  detail.value = await getAction(`/finance/collections/companies/${row.groupKey}`)
  statementFilter.value = 'all'
  invoiceFilter.value = 'all'
  companyVisible.value = true
}
async function refreshCompany() {
  const groupKey = detail.value.customer?.groupKey
  const [board, current] = await Promise.all([
    getAction<any[]>('/finance/collections/companies'),
    groupKey ? getAction<any>(`/finance/collections/companies/${groupKey}`) : Promise.resolve(null)
  ])
  companies.value = board
  if (current) detail.value = current
  if (statementVisible.value && statementDetail.value.statement?.id)
    statementDetail.value = await getAction(`/finance/collections/${statementDetail.value.statement.id}`)
}
async function openStatementDetail(row: any) {
  statementDetail.value = await getAction(`/finance/collections/${row.id}`)
  statementVisible.value = true
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
    await refreshCompany()
    ElMessage.success('对账单金额已修改')
  } finally { amountSubmitting.value = false }
}
async function deleteStatement(row: any) {
  const groupKey = detail.value.customer?.groupKey
  await remove('/finance/statements', row.id)
  if (statementDetail.value.statement?.id === row.id) statementVisible.value = false
  await loadCompanies()
  const companyStillExists = companies.value.some(item => item.groupKey === groupKey)
  if (groupKey && companyStillExists) {
    detail.value = await getAction(`/finance/collections/companies/${groupKey}`)
  } else {
    companyVisible.value = false
    detail.value = {}
  }
  ElMessage.success('对账单已删除')
}
function openCompanyPayment(row?: any) {
  editingPayment.value = row || undefined
  reset(companyForm, row ? {
    statementId: null,
    amount: Number(row.amount || 0),
    paymentDate: row.paymentDate || null,
    method: row.method || '银行转账',
    remark: row.remark || ''
  } : { statementId: null, amount: undefined, paymentDate: today(), method: '银行转账', remark: '' })
  clearErrors(companyErrors)
  companyPaymentVisible.value = true
}
function openStatementPayment(row: any) {
  openCompanyPayment()
  companyForm.amount = Number(row.arrearsAmount)
}
async function saveCompanyPayment() {
  clearErrors(companyErrors)
  if (!positive(companyForm.amount)) companyErrors.amount = '到账金额未填写'
  if (!companyForm.paymentDate && editingPayment.value?.sourceSequence == null) companyErrors.paymentDate = '到账日期未填写'
  if (firstError(companyErrors)) return ElMessage.error(firstError(companyErrors))
  submitting.value = true
  try {
    const groupKey = detail.value.customer.groupKey
    let allocationCount = 0
    if (['COMPANY', 'PAYMENT'].includes(editingPayment.value?.recordType)) {
      const result = await update<any>(`/finance/customer-summaries/${groupKey}/payments`, editingPayment.value.paymentBatchNo, companyForm)
      allocationCount = result.allocations?.length || 0
    } else if (editingPayment.value?.recordType === 'STATEMENT') {
      await update('/finance/receipts', editingPayment.value.id, {
        amount: companyForm.amount,
        receiptDate: companyForm.paymentDate,
        method: companyForm.method,
        remark: companyForm.remark
      })
      allocationCount = 1
    } else {
      const result = await postAction<any>(`/finance/customer-summaries/${groupKey}/payments`, companyForm)
      allocationCount = result.allocations?.length || 0
    }
    companyPaymentVisible.value = false
    editingPayment.value = undefined
    await refreshCompany()
    ElMessage.success(allocationCount > 1 ? `到账已保存，自动核销 ${allocationCount} 笔欠款` : '到账记录已保存')
  } finally { submitting.value = false }
}
async function deletePayment(row: any) {
  if (['COMPANY', 'PAYMENT'].includes(row.recordType)) {
    await remove(`/finance/customer-summaries/${detail.value.customer.groupKey}/payments`, row.paymentBatchNo)
  } else {
    await remove('/finance/receipts', row.id)
  }
  await refreshCompany()
  ElMessage.success('到账记录已删除，欠款已恢复')
}
function openSettlement(row: any) {
  selectedStatement.value = row
  clearErrors(settlementErrors)
  reset(settlementForm, { method: 'CASH', arrived: false, amount: Number(row.arrearsAmount || 0), transactionDate: today(), invoiceNo: '', remark: '' })
  settlementVisible.value = true
}
async function saveSettlement() {
  clearErrors(settlementErrors)
  if (settlementForm.method === 'INVOICE') {
    if (!String(settlementForm.invoiceNo || '').trim()) settlementErrors.invoiceNo = '发票号未填写'
    if (!positive(settlementForm.amount)) settlementErrors.amount = '开票金额未填写'
    if (!settlementForm.transactionDate) settlementErrors.transactionDate = '开票日期未填写'
  }
  if (firstError(settlementErrors)) return ElMessage.error(firstError(settlementErrors))
  submitting.value = true
  try {
    await postAction(`/finance/collections/${selectedStatement.value.id}/register`, settlementForm)
    settlementVisible.value = false
    await refreshCompany()
    ElMessage.success('结算方式已登记')
  } finally { submitting.value = false }
}
function reset(target: Record<string, any>, value: Record<string, any>) { Object.keys(target).forEach(key => delete target[key]); Object.assign(target, value) }
function clearErrors(errors: Record<string, string>) { Object.keys(errors).forEach(key => delete errors[key]) }
function firstError(errors: Record<string, string>) { return Object.values(errors)[0] || '' }
function positive(value: any) { return Number.isFinite(Number(value)) && Number(value) > 0 }
function methodText(method?: string) { return method === 'CASH' ? '现汇' : method === 'INVOICE' ? '开票' : '未登记' }
function statusType(status?: string) { return status === 'PAID' ? 'success' : status === 'PARTIALLY_PAID' ? 'warning' : status === 'INVOICED_PENDING' ? 'primary' : 'info' }
function statementRowClass({ row }: any) { return row.settlementStatus === 'PAID' ? 'statement-paid-row' : 'statement-unpaid-row' }
function formatDate(value?: string) { return value ? value.slice(0, 10) : '-' }
function today() { const now = new Date(); return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10) }

const { currentPage, pageSize, total, pagedRows, rowIndex } = useListPagination(filteredCompanies, [keyword, debtFilter])
onMounted(loadCompanies)
usePageAutoRefresh(loadCompanies, () => companyVisible.value || companyPaymentVisible.value || settlementVisible.value || amountVisible.value)
</script>

<style scoped>
.latest-payment-cell { align-items: flex-start; display: flex; flex-direction: column; gap: 4px; padding: 2px 0; }
.detail-shell { display: flex; flex-direction: column; gap: 18px; }
.compact-detail { gap: 14px; }
.detail-dialog-actions { align-items: center; display: flex; justify-content: flex-end; gap: 10px; }
.detail-title-row, .section-heading { align-items: center; display: flex; justify-content: space-between; gap: 16px; }
.customer-title { color: var(--oms-heading); font-size: 20px; font-weight: 760; margin-bottom: 4px; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.allocation-detail { padding: 12px 24px; display: grid; gap: 10px; }
.summary-grid-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.summary-grid > div { border: 1px solid var(--oms-border); border-radius: 8px; min-width: 0; padding: 12px; }
.summary-grid span { color: var(--oms-sub); display: block; font-size: 12px; margin-bottom: 6px; }
.summary-grid strong { display: block; overflow-wrap: anywhere; }
.debt-text { color: #c2410c; }
.section-title { color: var(--oms-heading); font-size: 15px; font-weight: 700; }
.statement-filters { align-items: center; display: flex; flex-wrap: wrap; gap: 10px; justify-content: flex-end; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.full-control { width: 100%; }
:deep(.statement-unpaid-row td.el-table__cell) { background: #fff; color: #1f2937; font-weight: 620; }
:deep(.statement-unpaid-row td.el-table__cell:first-child) { box-shadow: inset 3px 0 0 #d97706; }
:deep(.statement-paid-row td.el-table__cell) { background: #f6f7f9; color: #8a94a3; }
@media (max-width: 820px) {
  .detail-title-row, .section-heading { align-items: stretch; flex-direction: column; }
  .statement-filters { align-items: stretch; flex-direction: column; }
  .summary-grid, .summary-grid-two, .form-grid { grid-template-columns: 1fr; }
}
</style>
