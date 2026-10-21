<template>
  <div class="page">
    <div class="page-heading heading-actions">
      <div>
        <div class="page-title">对账已开票</div>
        <div class="muted">集中查看采用开票结算的对账单、回款状态和发票附件。</div>
      </div>
      <el-button type="primary" @click="openInvoice()">
        <el-icon><Plus /></el-icon>
        新增发票
      </el-button>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="客户 / 对账单号 / 发票号" clearable />
        </div>
        <div class="filter-field">
          <div class="filter-label">付款状态</div>
          <el-select v-model="statusFilter" placeholder="全部状态" clearable>
            <el-option label="已开票待回款" value="INVOICED_PENDING" />
            <el-option label="部分到账" value="PARTIALLY_PAID" />
            <el-option label="已完成付款" value="PAID" />
          </el-select>
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <span class="table-count">共 {{ filteredRows.length }} 条</span>
        <el-button @click="load(true)">刷新</el-button>
      </div>
      <el-table :data="pagedRows" row-key="id" height="calc(100vh - 300px)" :fit="false" border stripe>
        <template #empty>
          <div v-if="keyword.trim()" class="invoice-empty">
            <div class="invoice-empty-title">已开票记录中没有找到“{{ keyword.trim() }}”</div>
            <div class="muted">未开票或尚未登记结算方式的对账单不会显示在这里。</div>
            <el-button type="primary" plain @click="searchAllStatements">
              <el-icon><Search /></el-icon>
              去全部对账单查找
            </el-button>
          </div>
          <span v-else class="muted">暂无数据</span>
        </template>
        <el-table-column type="index" :index="rowIndex" label="#" width="50" fixed />
        <el-table-column prop="statementNo" label="对账单号" width="170" fixed show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="220" show-overflow-tooltip />
        <el-table-column prop="receivableAmount" label="账单金额" width="130" :formatter="moneyColumn" />
        <el-table-column prop="invoicedAmount" label="已开票" width="130" :formatter="moneyColumn" />
        <el-table-column prop="receivedAmount" label="已到账" width="130" :formatter="moneyColumn" />
        <el-table-column prop="arrearsAmount" label="剩余欠款" width="130" :formatter="moneyColumn" />
        <el-table-column prop="invoiceNos" label="发票号" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="140">
          <template #default="{ row }"><el-tag :type="statusType(row.settlementStatus)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="remainingInvoice(row) <= 0" @click="openInvoice(row)">新增发票</el-button>
            <el-button link @click="openDetail(row)">详情</el-button>
            <el-button link type="danger" @click="deleteStatement(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="PAGE_SIZE_OPTIONS"
          :total="filteredRows.length"
          layout="total, sizes, prev, pager, next, jumper"
        />
      </div>
    </div>

    <el-drawer v-model="detailVisible" title="开票与回款详情" size="76%">
      <div v-if="detail.statement" class="detail-shell">
        <div class="summary-grid">
          <div><span>对账单号</span><strong>{{ detail.statement.statementNo }}</strong></div>
          <div><span>客户</span><strong>{{ detail.statement.customerName }}</strong></div>
          <div><span>账单金额</span><strong>{{ money(detail.statement.receivableAmount) }}</strong></div>
          <div><span>剩余欠款</span><strong>{{ money(detail.statement.arrearsAmount) }}</strong></div>
        </div>
        <div class="detail-heading">
          <div class="section-title">发票信息</div>
          <el-tag :type="statusType(detail.statement.settlementStatus)">{{ detail.statement.status }}</el-tag>
        </div>
        <el-table :data="detail.invoices || []" max-height="360" border stripe>
          <el-table-column prop="invoiceNo" label="发票号" min-width="210" show-overflow-tooltip />
          <el-table-column prop="invoiceDate" label="开票日期" width="120" />
          <el-table-column prop="amount" label="开票金额" width="130" :formatter="moneyColumn" />
          <el-table-column prop="taxRate" label="税率" width="100" />
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
          <el-table-column label="附件" min-width="260">
            <template #default="{ row }">
              <div v-if="attachmentsFor(row.id).length" class="attachment-list">
                <el-button v-for="file in attachmentsFor(row.id)" :key="file.id" link type="primary" @click="downloadAttachment(file)">
                  <el-icon><Download /></el-icon>
                  {{ file.originalName }}
                </el-button>
              </div>
              <span v-else class="muted">暂无附件</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="section-title">到账记录</div>
        <el-table :data="detail.receipts || []" max-height="260" border stripe>
          <el-table-column label="收款单号" width="210"><template #default="{ row }">{{ row.paymentNo || row.receiptNo }}</template></el-table-column>
          <el-table-column prop="receiptDate" label="到账日期" width="120" />
          <el-table-column prop="amount" label="到账金额" width="130" :formatter="moneyColumn" />
          <el-table-column prop="method" label="收款方式" width="130" />
          <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        </el-table>
      </div>
    </el-drawer>

    <el-dialog v-model="invoiceVisible" title="新增发票" width="640px" destroy-on-close @closed="resetInvoiceRecognition">
      <el-form label-position="top">
        <el-form-item label="对账单" required :error="errors.statementId">
          <el-select v-model="invoiceForm.statementId" filterable class="full-control" :disabled="statementLocked" :loading="candidatesLoading" @change="syncCandidateAmount">
            <el-option
              v-for="item in candidates"
              :key="item.id"
              :label="`${item.statementNo} · ${item.customerName} · 可开票 ${money(remainingInvoice(item))}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="发票号" required :error="errors.invoiceNo"><el-input v-model="invoiceForm.invoiceNo" maxlength="64" @input="markInvoiceNoManual" /></el-form-item>
          <el-form-item label="开票日期" required :error="errors.invoiceDate"><el-date-picker v-model="invoiceForm.invoiceDate" type="date" value-format="YYYY-MM-DD" class="full-control" /></el-form-item>
          <el-form-item label="开票金额" required :error="errors.amount"><el-input-number v-model="invoiceForm.amount" :min="0" :max="currentInvoiceLimit" :step="0.001" :controls="false" class="full-control" @change="markInvoiceAmountManual" /></el-form-item>
          <el-form-item label="税率"><el-input-number v-model="invoiceForm.taxRate" :min="0" :precision="4" :controls="false" class="full-control" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="invoiceForm.remark" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        <el-form-item label="发票附件">
          <el-upload v-model:file-list="invoiceFiles" drag multiple action="#" :auto-upload="false" class="invoice-upload" :on-change="handleInvoiceFileChange" :on-remove="handleInvoiceFileRemove">
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div>拖拽文件到这里，或点击选择多个文件</div>
          </el-upload>
          <div v-if="invoiceRecognitionText" class="recognition-status" :class="`is-${invoiceRecognitionState}`">
            <el-icon v-if="invoiceRecognizing" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="invoiceRecognitionState === 'success'"><CircleCheck /></el-icon>
            <el-icon v-else><Warning /></el-icon>
            <span>{{ invoiceRecognitionText }}</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invoiceVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="saveInvoice">保存发票</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import { formatMoney as money, moneyColumn, decimalSubtract } from '@/format/money'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { CircleCheck, Download, Loading, Plus, Search, UploadFilled, Warning } from '@element-plus/icons-vue'
import 'element-plus/theme-chalk/el-message-box.css'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { getAction, postAction, remove } from '@/api/crud'
import http from '@/api/http'
import { usePageAutoRefresh } from '@/composables/usePageAutoRefresh'

const rows = ref<any[]>([])
const router = useRouter()
const candidates = ref<any[]>([])
const keyword = ref('')
const statusFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const detailVisible = ref(false)
const invoiceVisible = ref(false)
const statementLocked = ref(false)
const submitting = ref(false)
const candidatesLoading = ref(false)
const invoiceRecognizing = ref(false)
const invoiceRecognitionState = ref<'idle' | 'loading' | 'success' | 'warning'>('idle')
const invoiceRecognitionText = ref('')
const detail = ref<any>({})
const invoiceFiles = ref<any[]>([])
const invoiceForm = reactive<any>({ statementId: undefined, invoiceNo: '', invoiceDate: '', amount: undefined, taxRate: undefined, remark: '' })
const errors = reactive<Record<string, string>>({})
const invoiceFieldSource = reactive({ invoiceNo: 'default', amount: 'default' })
const recognizedInvoiceFields = reactive({ invoiceNo: false, amount: false })
const recognitionQueue: Array<{ file: File, generation: number }> = []
let recognitionGeneration = 0

const filteredRows = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => {
    if (statusFilter.value && row.settlementStatus !== statusFilter.value) return false
    return !key || [row.customerName, row.statementNo, row.invoiceNos].join(' ').toLowerCase().includes(key)
  })
})
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})
const currentCandidate = computed(() => candidates.value.find(item => item.id === invoiceForm.statementId)
  || rows.value.find(item => item.id === invoiceForm.statementId))
const currentInvoiceLimit = computed(() => currentCandidate.value
  ? remainingInvoice(currentCandidate.value)
  : Number.MAX_SAFE_INTEGER)
let rowsSignature = ''

async function load(force = false) {
  const board = await getAction<any[]>('/finance/invoices/statement-board')
  const nextSignature = JSON.stringify(board)
  if (force || nextSignature !== rowsSignature) {
    rows.value = board
    rowsSignature = nextSignature
    const lastPage = Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value))
    currentPage.value = Math.min(currentPage.value, lastPage)
  }
}
function rowIndex(index: number) {
  return (currentPage.value - 1) * pageSize.value + index + 1
}
function searchAllStatements() {
  void router.push({ path: '/statements', query: { keyword: keyword.value.trim() } })
}
async function openDetail(row: any) {
  detail.value = await getAction(`/finance/statements/${row.id}/detail`)
  detailVisible.value = true
}
async function deleteStatement(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认删除对账单 ${row.statementNo || ''}？发票和附件将撤销，发货记录恢复为未对账；实际到账流水会保留并重新分配，无可分配欠款时转为预收款。`,
      '删除并回档',
      {
        type: 'warning',
        customClass: 'statement-delete-confirm',
        confirmButtonText: '删除并回档',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  await remove('/finance/statements', row.id)
  if (detail.value.statement?.id === row.id) detailVisible.value = false
  await load(true)
  ElMessage.success('对账单已删除，发货与收款数据已同步回档')
}
async function openInvoice(row?: any) {
  clearErrors()
  resetInvoiceRecognition()
  Object.assign(invoiceForm, { statementId: row?.id, invoiceNo: '', invoiceDate: today(), amount: row ? remainingInvoice(row) : undefined, taxRate: undefined, remark: '' })
  statementLocked.value = Boolean(row)
  invoiceFiles.value = []
  invoiceVisible.value = true
  if (!row) {
    candidatesLoading.value = true
    candidates.value = []
    try {
      candidates.value = await getAction<any[]>('/finance/invoices/candidates')
    } finally {
      candidatesLoading.value = false
    }
  }
}
function syncCandidateAmount() {
  if (invoiceFieldSource.amount === 'default') {
    invoiceForm.amount = currentCandidate.value ? remainingInvoice(currentCandidate.value) : undefined
  }
}
async function saveInvoice() {
  clearErrors()
  if (!invoiceForm.statementId) errors.statementId = '请选择对账单'
  if (!String(invoiceForm.invoiceNo || '').trim()) errors.invoiceNo = '发票号未填写'
  if (!invoiceForm.invoiceDate) errors.invoiceDate = '开票日期未填写'
  if (!Number.isFinite(Number(invoiceForm.amount)) || Number(invoiceForm.amount) <= 0) errors.amount = '开票金额未填写'
  if (currentCandidate.value && Number(invoiceForm.amount) > remainingInvoice(currentCandidate.value)) {
    errors.amount = `开票金额不能超过可开票金额 ${money(remainingInvoice(currentCandidate.value))}`
  }
  const firstError = Object.values(errors)[0]
  if (firstError) return ElMessage.error(firstError)
  submitting.value = true
  try {
    const invoice = await postAction<any>('/finance/invoices', { ...invoiceForm })
    const failed: string[] = []
    for (const item of invoiceFiles.value) {
      if (!item.raw) continue
      try {
        await uploadAttachment(invoice.id, item.raw)
      } catch {
        failed.push(item.name || item.raw.name)
      }
    }
    invoiceVisible.value = false
    await load(true)
    if (detailVisible.value && detail.value.statement?.id === invoiceForm.statementId) {
      detail.value = await getAction(`/finance/statements/${invoiceForm.statementId}/detail`)
    }
    if (failed.length) ElMessage.warning(`发票已保存，${failed.length} 个附件上传失败：${failed.join('、')}`)
    else ElMessage.success('发票及附件已保存')
  } finally {
    submitting.value = false
  }
}
async function uploadAttachment(invoiceId: number, file: File) {
  const data = new FormData()
  data.append('file', file)
  data.append('businessType', 'INVOICE_ATTACHMENT')
  data.append('businessId', String(invoiceId))
  await http.post('/files/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
}
function markInvoiceNoManual() {
  invoiceFieldSource.invoiceNo = 'manual'
  recognizedInvoiceFields.invoiceNo = false
}
function markInvoiceAmountManual() {
  invoiceFieldSource.amount = 'manual'
  recognizedInvoiceFields.amount = false
}
function resetInvoiceRecognition() {
  recognitionGeneration += 1
  recognitionQueue.length = 0
  invoiceRecognizing.value = false
  invoiceRecognitionState.value = 'idle'
  invoiceRecognitionText.value = ''
  invoiceFieldSource.invoiceNo = 'default'
  invoiceFieldSource.amount = 'default'
  recognizedInvoiceFields.invoiceNo = false
  recognizedInvoiceFields.amount = false
}
function supportsInvoiceRecognition(file: File) {
  const type = String(file.type || '').toLowerCase()
  const name = String(file.name || '').toLowerCase()
  return type.startsWith('image/') || type === 'application/pdf' || name.endsWith('.pdf')
}
function handleInvoiceFileChange(file: any) {
  const raw = file?.raw as File | undefined
  if (!raw) return
  if (!supportsInvoiceRecognition(raw)) {
    ElMessage.info('附件已加入；自动识别支持图片和 PDF，当前文件可继续手动填写')
    return
  }
  if (recognizedInvoiceFields.invoiceNo && recognizedInvoiceFields.amount) return
  recognitionQueue.push({ file: raw, generation: recognitionGeneration })
  if (!invoiceRecognizing.value) void processInvoiceRecognitionQueue()
}
function handleInvoiceFileRemove(_file: any, files: any[]) {
  const hasRecognizableFile = files.some(item => item.raw && supportsInvoiceRecognition(item.raw))
  if (!hasRecognizableFile) {
    recognizedInvoiceFields.invoiceNo = false
    recognizedInvoiceFields.amount = false
    invoiceRecognitionState.value = 'idle'
    invoiceRecognitionText.value = ''
  }
}
async function processInvoiceRecognitionQueue() {
  const generation = recognitionGeneration
  invoiceRecognizing.value = true
  invoiceRecognitionState.value = 'loading'
  invoiceRecognitionText.value = '正在识别发票信息...'
  let lastFailure = ''
  try {
    while (recognitionQueue.length) {
      const next = recognitionQueue.shift()!
      if (next.generation !== recognitionGeneration) continue
      const body = new FormData()
      body.append('file', next.file)
      try {
        const result = await http.post<any, any>('/finance/invoices/recognize-document', body, {
          headers: { 'Content-Type': 'multipart/form-data' },
          timeout: 60000,
          skipErrorToast: true
        })
        if (generation !== recognitionGeneration || !invoiceVisible.value) return
        let amountConflict = false
        if (result.invoiceNo && invoiceFieldSource.invoiceNo !== 'manual') {
          invoiceForm.invoiceNo = String(result.invoiceNo)
          invoiceFieldSource.invoiceNo = 'recognized'
          recognizedInvoiceFields.invoiceNo = true
        }
        const recognizedAmount = Number(result.amount)
        if (Number.isFinite(recognizedAmount) && recognizedAmount > 0 && invoiceFieldSource.amount !== 'manual') {
          const limit = currentCandidate.value ? remainingInvoice(currentCandidate.value) : undefined
          if (limit !== undefined && recognizedAmount > limit) {
            amountConflict = true
          } else {
            invoiceForm.amount = recognizedAmount
            invoiceFieldSource.amount = 'recognized'
            recognizedInvoiceFields.amount = true
          }
        }
        if (amountConflict) {
          invoiceRecognitionState.value = 'warning'
          invoiceRecognitionText.value = `识别金额 ${money(recognizedAmount)} 超过该对账单可开票金额，请手动核对`
          recognitionQueue.length = 0
          ElMessage.warning(invoiceRecognitionText.value)
          return
        }
        if (recognizedInvoiceFields.invoiceNo || recognizedInvoiceFields.amount) {
          const fields = [recognizedInvoiceFields.invoiceNo ? '发票号' : '', recognizedInvoiceFields.amount ? '金额' : ''].filter(Boolean).join('和')
          invoiceRecognitionState.value = 'success'
          invoiceRecognitionText.value = `已自动填入${fields}，请核对后保存`
          if (recognizedInvoiceFields.invoiceNo && recognizedInvoiceFields.amount) {
            recognitionQueue.length = 0
            ElMessage.success('发票号和金额识别完成')
            return
          }
        } else {
          lastFailure = '未识别到发票号或价税合计'
        }
      } catch (error: any) {
        if (generation !== recognitionGeneration) return
        lastFailure = error?.response?.data?.message || error?.message || '识别请求失败'
      }
    }
    if (!recognizedInvoiceFields.invoiceNo && !recognizedInvoiceFields.amount) {
      invoiceRecognitionState.value = 'warning'
      invoiceRecognitionText.value = `${lastFailure || '自动识别失败'}，可继续手动填写`
      ElMessage.warning(invoiceRecognitionText.value)
    }
  } finally {
    if (generation === recognitionGeneration) invoiceRecognizing.value = false
    if (recognitionQueue.some(item => item.generation === recognitionGeneration) && !invoiceRecognizing.value) {
      void processInvoiceRecognitionQueue()
    }
  }
}
function attachmentsFor(invoiceId: number) { return detail.value.invoiceAttachments?.[invoiceId] || [] }
async function downloadAttachment(file: any) {
  const blob = await http.get<Blob, Blob>(`/files/download/${file.id}`, { responseType: 'blob' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = file.originalName || '发票附件'
  link.click()
  URL.revokeObjectURL(url)
}
function remainingInvoice(row: any) { return Math.max(0, Number(decimalSubtract(row.receivableAmount, row.invoicedAmount))) }
function statusType(status?: string) { return status === 'PAID' ? 'success' : status === 'PARTIALLY_PAID' ? 'warning' : 'primary' }
function clearErrors() { Object.keys(errors).forEach(key => delete errors[key]) }
function today() {
  const now = new Date()
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
}

onMounted(() => load(true))
usePageAutoRefresh(load, () => detailVisible.value || invoiceVisible.value)
watch([keyword, statusFilter], () => { currentPage.value = 1 })
watch(pageSize, () => { currentPage.value = 1 })
</script>

<style scoped>
.heading-actions { align-items: center; display: flex; justify-content: space-between; gap: 16px; }
.detail-shell { display: flex; flex-direction: column; gap: 18px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.summary-grid > div { border: 1px solid var(--oms-border); border-radius: 8px; min-width: 0; padding: 12px; }
.summary-grid span { color: var(--oms-sub); display: block; font-size: 12px; margin-bottom: 6px; }
.summary-grid strong { display: block; overflow-wrap: anywhere; }
.detail-heading { align-items: center; display: flex; justify-content: space-between; }
.section-title { color: var(--oms-heading); font-size: 15px; font-weight: 700; }
.attachment-list { align-items: flex-start; display: flex; flex-direction: column; }
.attachment-list :deep(.el-button) { height: auto; margin: 0; max-width: 100%; white-space: normal; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.full-control { width: 100%; }
.invoice-upload { width: 100%; }
.invoice-upload :deep(.el-upload), .invoice-upload :deep(.el-upload-dragger) { width: 100%; }
.upload-icon { color: var(--el-color-primary); font-size: 38px; margin-bottom: 8px; }
.recognition-status { align-items: center; display: flex; gap: 7px; margin-top: 10px; font-size: 13px; line-height: 20px; }
.recognition-status.is-loading { color: var(--el-color-primary); }
.recognition-status.is-success { color: var(--el-color-success); }
.recognition-status.is-warning { color: var(--el-color-warning); }
.invoice-empty { align-items: center; display: flex; flex-direction: column; gap: 10px; padding: 32px 16px; }
.invoice-empty-title { color: var(--oms-heading); font-size: 15px; font-weight: 700; }
:global(.statement-delete-confirm.el-message-box) { width: min(420px, calc(100vw - 24px)) !important; }
:global(.statement-delete-confirm .el-message-box__message) { overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .heading-actions { align-items: stretch; flex-direction: column; }
  .summary-grid, .form-grid { grid-template-columns: 1fr; }
  :global(.statement-delete-confirm .el-message-box__btns) { display: grid; gap: 8px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  :global(.statement-delete-confirm .el-message-box__btns .el-button) { margin-left: 0; width: 100%; }
}
</style>
