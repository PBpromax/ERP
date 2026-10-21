<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">工人管理</div>
      <div class="muted">维护生产工人和扫码入口，查看每个工人的生产记录。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="生产人员姓名" clearable />
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="left-actions">
          <el-button type="primary" @click="openWorkerDialog">新增</el-button>
        </div>
        <div class="right-actions">
          <span class="table-count">共 {{ filteredWorkers.length }} 人</span>
          <el-button @click="loadWorkers">刷新</el-button>
        </div>
      </div>
      <div class="worker-grid">
        <div v-for="worker in filteredWorkers" :key="worker.id" class="worker-card">
          <button class="worker-bubble" @click="openWorker(worker)">{{ worker.name }}</button>
          <div class="worker-actions">
            <el-button link type="primary" @click="openWorkerEntry(worker)">二维码</el-button>
            <el-popconfirm title="确认删除该生产人员？" @confirm="deleteWorker(worker)">
              <template #reference><el-button link type="danger">删除</el-button></template>
            </el-popconfirm>
          </div>
        </div>
      </div>

      <el-empty v-if="filteredWorkers.length === 0" description="暂无生产人员" />

      <el-dialog v-model="workerDialogVisible" title="新增生产人员" width="420px">
        <el-form :model="workerForm" label-width="80px">
          <el-form-item label="姓名" required :error="workerErrors.name"><el-input v-model="workerForm.name" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="workerDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="saveWorker">保存</el-button>
        </template>
      </el-dialog>

      <el-drawer v-model="detailVisible" size="64%" :with-header="false">
        <div class="worker-detail-stack">
          <section class="worker-profile-head">
            <div class="worker-profile-title-row">
              <div class="worker-profile-name">{{ selectedWorker?.name || '工人' }}</div>
              <button class="drawer-close-button" type="button" @click="detailVisible = false">×</button>
            </div>
            <div class="worker-calendar">
              <div class="calendar-head">
                <button class="calendar-nav" type="button" @click="changeCalendarMonth(-1)">‹</button>
                <div class="calendar-title">{{ calendarTitle }}</div>
                <button class="calendar-nav" type="button" @click="changeCalendarMonth(1)">›</button>
              </div>
              <div class="calendar-weekdays">
                <span v-for="day in calendarWeekdays" :key="day">{{ day }}</span>
              </div>
              <div class="calendar-grid">
                <div
                  v-for="day in calendarDays"
                  :key="day.key"
                  class="calendar-day"
                  :class="{ 'is-empty': !day.date, 'has-record': day.hasRecord }"
                >
                  {{ day.label }}
                </div>
              </div>
            </div>
          </section>

          <section class="detail-section">
            <div class="detail-head">
              <div class="section-title">生产记录</div>
              <div class="detail-actions">
                <el-checkbox-button v-model="recordOnlyUnaudited">未审核</el-checkbox-button>
                <el-checkbox-button v-model="recordOnlyUnallocated">未分配完毕</el-checkbox-button>
                <el-button type="primary" @click="openRecordDialog">新增</el-button>
              </div>
            </div>

            <el-table :data="filteredRecordsWithDateDisplay" height="260" border stripe>
              <el-table-column prop="displayDate" label="日期" width="150" />
              <el-table-column prop="semiFinishedCode" label="生产产品" width="150" />
              <el-table-column label="合格数量/生产数量" width="170">
                <template #default="{ row }">{{ recordQuantityText(row) }}</template>
              </el-table-column>
              <el-table-column label="是否已审核" width="130">
                <template #default="{ row }">{{ row.audited ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column label="是否已分配完" width="150">
                <template #default="{ row }">{{ row.fullyAllocated ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="230">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openAuditDialog(row)">审核</el-button>
                  <el-button link type="success" @click="openAllocationDialog(row)">分配</el-button>
                  <el-popconfirm title="确认删除该生产记录？" @confirm="deleteRecord(row)">
                    <template #reference><el-button link type="danger">删除</el-button></template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
            <div class="table-pagination"><el-pagination v-model:current-page="recordPage" :page-size="recordPageSize" :total="recordTotal" layout="total, prev, pager, next" /><span class="muted">每页 {{ recordPageSize }} 条</span></div>
          </section>

          <section class="detail-section">
            <div class="detail-head">
              <div class="section-title">生产任务</div>
              <div class="detail-actions">
                <el-checkbox-button v-model="taskOnlyIncomplete">未完成任务</el-checkbox-button>
                <span class="table-count">共 {{ filteredWorkerTasks.length }} 条</span>
              </div>
            </div>

            <el-table :data="pagedWorkerTasks" height="260" border stripe>
              <el-table-column prop="planNo" label="计划单号" width="150" />
              <el-table-column prop="productCode" label="产品编号" width="220" show-overflow-tooltip />
              <el-table-column prop="productName" label="产品名称" width="280" show-overflow-tooltip />
              <el-table-column prop="productSpec" label="规格" width="220" show-overflow-tooltip />
              <el-table-column prop="completionText" label="完成进度" width="150" />
            </el-table>
            <div class="table-pagination"><el-pagination v-model:current-page="taskPage" :page-size="taskPageSize" :total="taskTotal" layout="total, prev, pager, next" /><span class="muted">每页 {{ taskPageSize }} 条</span></div>
          </section>
        </div>
      </el-drawer>

      <el-dialog v-model="recordDialogVisible" title="新增生产记录" width="620px">
        <el-form :model="recordForm" label-width="100px">
          <el-form-item label="日期" required :error="recordErrors.productionDate">
            <el-date-picker v-model="recordForm.productionDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="生产产品" required :error="recordErrors.semiFinishedCode">
            <el-autocomplete
              v-model="recordForm.semiFinishedCode"
              :fetch-suggestions="stockSuggestions"
              value-key="value"
              clearable
              style="width: 100%"
              @select="selectStock"
            >
              <template #default="{ item }">
                <div class="suggestion-main">{{ item.value }}</div>
                <div v-if="item.sub" class="suggestion-sub">{{ item.sub }}</div>
              </template>
            </el-autocomplete>
          </el-form-item>
          <el-form-item label="数量" required :error="recordErrors.qty"><el-input v-model="recordForm.qty" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="recordDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="saveRecord">保存</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="auditDialogVisible" title="审核生产记录" width="460px">
        <el-form :model="auditForm" label-width="110px">
          <el-form-item label="生产数量">
            <el-input :model-value="formatQty(auditForm.productionQty)" disabled />
          </el-form-item>
          <el-form-item label="合格数量">
            <el-input-number
              v-model="auditForm.qualifiedQty"
              :min="0"
              :max="Number(auditForm.productionQty || 0)"
              :precision="0"
              :step="1"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="auditDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="saveAudit">保存</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="allocationDialogVisible" title="分配生产记录" width="760px">
        <div class="allocation-summary">
          <div><span>生产产品</span><strong>{{ allocationRecord?.semiFinishedCode || '-' }}</strong></div>
          <div><span>合格数量</span><strong>{{ formatQty(allocationForm.qualifiedQty) }}</strong></div>
          <div><span>已分配</span><strong>{{ formatQty(allocationForm.allocatedQty) }}</strong></div>
          <div><span>剩余可分配</span><strong>{{ formatQty(allocationRemainingQty) }}</strong></div>
        </div>

        <el-table :data="allocationCandidates" height="260" border stripe>
          <el-table-column prop="planNo" label="计划单号" width="150" />
          <el-table-column prop="completionText" label="完成进度" width="130" />
          <el-table-column label="分配数量" width="190" fixed="right">
            <template #default="{ row }">
              <div class="allocation-input">
                <el-input-number
                  v-model="row.allocateQty"
                  :min="0"
                  :max="Number(Math.min(Number(row.remainingQty || 0), Number(allocationRemainingQty || 0)))"
                  :precision="0"
                  :step="1"
                  controls-position="right"
                  style="width: 112px"
                />
                <el-button link type="primary" @click="saveAllocation(row)">分配</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="allocation-history-title">已分配明细</div>
        <div v-if="allocationHistory.length" class="allocation-history">
          <div v-for="item in allocationHistory" :key="item.id" class="allocation-history-item">
            <span>{{ item.sourceOrderNo || item.planNo || '-' }}</span>
            <strong>{{ formatQty(item.qty) }}</strong>
          </div>
        </div>
        <el-empty v-else description="暂无分配明细" />

        <template #footer>
          <el-button @click="allocationDialogVisible = false">关闭</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="entryDialogVisible" title="工人扫码入口" width="460px">
        <div class="entry-box">
          <div class="entry-name">{{ entryInfo.workerName }}</div>
          <img v-if="entryQrUrl" class="entry-qr" :src="entryQrUrl" alt="工人入口二维码" />
          <el-input ref="entryUrlInputRef" v-model="entryInfo.url" readonly>
            <template #append>
              <el-button @click="copyEntryUrl">复制</el-button>
            </template>
          </el-input>
        </div>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useListPagination } from '@/composables/useListPagination'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { create, getAction, list, postAction, remove } from '@/api/crud'
import { productAliases } from '@/product/productSearch'

const workers = ref<any[]>([])
const records = ref<any[]>([])
const workerTasks = ref<any[]>([])
const assignedProducts = ref<any[]>([])
const keyword = ref('')
const selectedWorker = ref<any>(null)
const workerDialogVisible = ref(false)
const detailVisible = ref(false)
const recordDialogVisible = ref(false)
const auditDialogVisible = ref(false)
const allocationDialogVisible = ref(false)
const entryDialogVisible = ref(false)
const workerForm = reactive<any>({})
const workerErrors = reactive<Record<string, string>>({})
const recordErrors = reactive<Record<string, string>>({})
const recordForm = reactive<any>({})
const auditForm = reactive<any>({})
const allocationForm = reactive<any>({})
const entryInfo = reactive<any>({})
const entryUrlInputRef = ref<any>(null)
const auditRecord = ref<any>(null)
const allocationRecord = ref<any>(null)
const allocationCandidates = ref<any[]>([])
const allocationHistory = ref<any[]>([])
const recordOnlyUnaudited = ref(false)
const recordOnlyUnallocated = ref(false)
const taskOnlyIncomplete = ref(false)
const calendarMonth = ref(monthStart(new Date()))
const calendarWeekdays = ['一', '二', '三', '四', '五', '六', '日']

const filteredWorkers = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return workers.value
  return workers.value.filter(worker => String(worker.name || '').toLowerCase().includes(k))
})

const filteredRecords = computed(() => records.value.filter(record => {
  if (recordOnlyUnaudited.value && record.audited) return false
  if (recordOnlyUnallocated.value && record.fullyAllocated) return false
  return true
}))

const { currentPage: recordPage, pageSize: recordPageSize, total: recordTotal, pagedRows: recordPageRows } = useListPagination(filteredRecords, [selectedWorker, recordOnlyUnaudited, recordOnlyUnallocated])

const filteredRecordsWithDateDisplay = computed(() => {
  let previousDate = ''
  return recordPageRows.value.map(record => {
    const displayDate = record.productionDate === previousDate ? '' : record.productionDate
    previousDate = record.productionDate
    return { ...record, displayDate }
  })
})

const filteredWorkerTasks = computed(() => {
  const rows = workerTasks.value.filter(task => !taskOnlyIncomplete.value || !task.completed)
  return [...rows].sort((a, b) => {
    const completedDiff = Number(Boolean(a.completed)) - Number(Boolean(b.completed))
    if (completedDiff !== 0) return completedDiff
    return String(a.planNo || '').localeCompare(String(b.planNo || ''), 'zh-Hans-CN')
  })
})

const { currentPage: taskPage, pageSize: taskPageSize, total: taskTotal, pagedRows: pagedWorkerTasks } = useListPagination(filteredWorkerTasks, [selectedWorker, taskOnlyIncomplete])

const allocationRemainingQty = computed(() => Math.max(0, Number(allocationForm.qualifiedQty || 0) - Number(allocationForm.allocatedQty || 0)))

const recordDateSet = computed(() => new Set(records.value
  .map(record => String(record.productionDate || '').slice(0, 10))
  .filter(Boolean)))

const calendarTitle = computed(() => {
  const date = calendarMonth.value
  return `${date.getFullYear()}年${date.getMonth() + 1}月`
})

const calendarDays = computed(() => {
  const first = calendarMonth.value
  const year = first.getFullYear()
  const month = first.getMonth()
  const firstWeekday = (first.getDay() + 6) % 7
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const cells: Array<{ key: string; date: string; label: string; hasRecord: boolean }> = []
  for (let i = 0; i < firstWeekday; i += 1) {
    cells.push({ key: `empty-${i}`, date: '', label: '', hasRecord: false })
  }
  for (let day = 1; day <= daysInMonth; day += 1) {
    const date = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    cells.push({
      key: date,
      date,
      label: String(day),
      hasRecord: recordDateSet.value.has(date)
    })
  }
  return cells
})

const entryQrUrl = computed(() => {
  if (!entryInfo.url) return ''
  return `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(entryInfo.url)}`
})

watch(() => recordForm.semiFinishedCode, value => {
  const product = assignedProducts.value.find(item => item.code === value)
  if (product) {
    recordForm.semiFinishedCode = product.code
  }
})

async function loadWorkers() {
  workers.value = await list('/production/records')
}

function openWorkerDialog() {
  Object.keys(workerForm).forEach(key => delete workerForm[key])
  clearErrors(workerErrors)
  workerDialogVisible.value = true
}

async function saveWorker() {
  clearErrors(workerErrors)
  if (!String(workerForm.name || '').trim()) { workerErrors.name = '姓名未填写'; return ElMessage.error('姓名未填写') }
  await create('/production/records', workerForm)
  ElMessage.success('保存成功')
  workerDialogVisible.value = false
  await loadWorkers()
}

async function deleteWorker(worker: any) {
  await remove('/production/records', worker.id)
  ElMessage.success('生产人员已删除')
  if (selectedWorker.value?.id === worker.id) {
    selectedWorker.value = null
    records.value = []
    detailVisible.value = false
  }
  await loadWorkers()
}

async function openWorkerEntry(worker: any) {
  const info = await getAction(`/production/records/${worker.id}/entry`)
  Object.assign(entryInfo, {
    ...info,
    url: buildWorkerEntryUrl(info.token)
  })
  entryDialogVisible.value = true
}

async function copyEntryUrl() {
  const url = String(entryInfo.url || '')
  if (!url) return
  try {
    if (window.isSecureContext && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url)
      ElMessage.success('链接已复制')
      return
    }
  } catch {
    // Some browsers block clipboard access on non-HTTPS pages; fall back below.
  }
  const input = entryUrlInputRef.value?.input as HTMLInputElement | undefined
  const textarea = document.createElement('textarea')
  textarea.value = url
  textarea.setAttribute('readonly', 'readonly')
  textarea.style.position = 'fixed'
  textarea.style.top = '-1000px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  const target = input || textarea
  target.focus()
  target.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (copied) {
    ElMessage.success('链接已复制')
    return
  }
  ElMessage.warning('浏览器限制自动复制，请手动选中链接复制')
}

async function openWorker(worker: any) {
  selectedWorker.value = worker
  detailVisible.value = true
  await Promise.all([loadRecords(), loadWorkerTasks(), loadAssignedProducts()])
  syncCalendarToLatestRecord()
}

async function loadRecords() {
  if (!selectedWorker.value) return
  records.value = await getAction(`/production/records/${selectedWorker.value.id}/items`)
}

async function loadWorkerTasks() {
  if (!selectedWorker.value) return
  workerTasks.value = await getAction(`/production/records/${selectedWorker.value.id}/tasks`)
}

async function loadAssignedProducts() {
  if (!selectedWorker.value) return
  assignedProducts.value = await getAction(`/production/records/${selectedWorker.value.id}/assigned-products`)
}

function openRecordDialog() {
  Object.keys(recordForm).forEach(key => delete recordForm[key])
  clearErrors(recordErrors)
  recordForm.productionDate = new Date().toISOString().slice(0, 10)
  recordDialogVisible.value = true
}

async function saveRecord() {
  if (!selectedWorker.value) return
  clearErrors(recordErrors)
  if (!recordForm.productionDate) recordErrors.productionDate = '日期未填写'
  if (!String(recordForm.semiFinishedCode || '').trim()) recordErrors.semiFinishedCode = '生产产品未填写'
  if (!Number.isFinite(Number(recordForm.qty)) || Number(recordForm.qty) <= 0) recordErrors.qty = '数量必须大于0'
  const recordError = Object.values(recordErrors)[0]
  if (recordError) return ElMessage.error(recordError)
  await postAction(`/production/records/${selectedWorker.value.id}/items`, {
    productionDate: recordForm.productionDate,
    semiFinishedCode: recordForm.semiFinishedCode,
    qty: recordForm.qty
  })
  ElMessage.success('生产记录已保存')
  recordDialogVisible.value = false
  await Promise.all([loadRecords(), loadWorkerTasks(), loadAssignedProducts()])
}

async function deleteRecord(row: any) {
  await remove('/production/records/items', row.id)
  ElMessage.success('生产记录已删除')
  await Promise.all([loadRecords(), loadWorkerTasks(), loadAssignedProducts()])
}

function openAuditDialog(row: any) {
  auditRecord.value = row
  Object.keys(auditForm).forEach(key => delete auditForm[key])
  auditForm.productionQty = Number(row.qty || 0)
  auditForm.qualifiedQty = Number(row.audited ? (row.qualifiedQty ?? 0) : (row.qty ?? 0))
  auditDialogVisible.value = true
}

async function saveAudit() {
  if (!auditRecord.value) return
  await postAction(`/production/records/items/${auditRecord.value.id}/audit`, {
    qualifiedQty: auditForm.qualifiedQty
  })
  ElMessage.success('审核已保存')
  auditDialogVisible.value = false
  await Promise.all([loadRecords(), loadWorkerTasks(), loadAssignedProducts()])
}

async function openAllocationDialog(row: any) {
  if (!row.audited) {
    ElMessage.warning('请先审核生产记录')
    return
  }
  allocationRecord.value = row
  Object.keys(allocationForm).forEach(key => delete allocationForm[key])
  allocationForm.qualifiedQty = Number(row.qualifiedQty || 0)
  allocationForm.allocatedQty = Number(row.allocatedQty || 0)
  allocationDialogVisible.value = true
  await loadAllocationData()
}

async function loadAllocationData() {
  if (!allocationRecord.value?.id) return
  const [candidates, history] = await Promise.all([
    getAction<any[]>(`/production/records/items/${allocationRecord.value.id}/allocation-candidates`),
    getAction<any[]>(`/production/records/items/${allocationRecord.value.id}/allocations`)
  ])
  allocationCandidates.value = candidates.map(row => ({
    ...row,
    allocateQty: Math.min(Number(row.remainingQty || 0), allocationRemainingQty.value)
  }))
  allocationHistory.value = history
}

async function saveAllocation(row: any) {
  if (!allocationRecord.value?.id) return
  const qty = Number(row.allocateQty || 0)
  if (!qty || qty <= 0) {
    ElMessage.error('请输入分配数量')
    return
  }
  await postAction(`/production/records/items/${allocationRecord.value.id}/allocate`, {
    assignmentId: row.assignmentId,
    qty
  })
  ElMessage.success('分配成功')
  await Promise.all([loadRecords(), loadWorkerTasks(), loadAssignedProducts()])
  const latest = records.value.find(record => record.id === allocationRecord.value.id)
  if (latest) {
    allocationRecord.value = latest
    allocationForm.qualifiedQty = Number(latest.qualifiedQty || 0)
    allocationForm.allocatedQty = Number(latest.allocatedQty || 0)
  }
  await loadAllocationData()
}

function clearErrors(errors: Record<string, string>) { Object.keys(errors).forEach(key => delete errors[key]) }

function stockSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim().toLowerCase()
  cb(assignedProducts.value
    .filter(product => {
      const text = [product.code, product.name, product.spec, product.planNo, product.sourceOrderNo, ...productAliases(product)].join(' ').toLowerCase()
      return !key || text.includes(key)
    })
    .slice(0, 8)
    .map(product => ({ value: product.code, sub: assignedProductSub(product), raw: product })))
}

function selectStock(item: any) {
  recordForm.semiFinishedCode = item.raw.code
}

function assignedProductSub(product: any) {
  return [
    product.name ? `名称：${product.name}` : '',
    product.spec ? `规格：${product.spec}` : '',
    productAliases(product).length ? `别称：${productAliases(product).join('、')}` : '',
    product.assignedQty != null ? `计划数量：${formatQty(product.assignedQty)}` : ''
  ].filter(Boolean).join('　')
}

function recordQuantityText(row: any) {
  const qualifiedQty = row.audited ? (row.qualifiedQty ?? row.qty) : 0
  return `${formatQty(qualifiedQty)}/${formatQty(row.qty)}`
}

function formatQty(value: any) {
  const number = Number(value || 0)
  if (!Number.isFinite(number)) return '0'
  return Number.isInteger(number) ? String(number) : String(number).replace(/\.?0+$/, '')
}

function buildWorkerEntryUrl(token: string) {
  if (!token) return ''
  return `${window.location.origin}/worker/production/${token}`
}

function monthStart(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

function changeCalendarMonth(offset: number) {
  const date = calendarMonth.value
  calendarMonth.value = new Date(date.getFullYear(), date.getMonth() + offset, 1)
}

function syncCalendarToLatestRecord() {
  const dates = records.value
    .map(record => String(record.productionDate || '').slice(0, 10))
    .filter(Boolean)
    .sort()
  const latest = dates[dates.length - 1]
  if (!latest) {
    calendarMonth.value = monthStart(new Date())
    return
  }
  const [year, month] = latest.split('-').map(Number)
  if (year && month) calendarMonth.value = new Date(year, month - 1, 1)
}

onMounted(async () => {
  await loadWorkers()
})
</script>

<style scoped>
.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail-head {
  justify-content: space-between;
  margin-bottom: 14px;
}

.detail-actions {
  align-items: center;
  display: flex;
  gap: 10px;
}

.worker-detail-stack {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.worker-profile-head {
  padding-bottom: 4px;
}

.worker-profile-title-row {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin-bottom: 18px;
}

.worker-profile-name {
  color: #111827;
  font-size: 32px;
  font-weight: 900;
  line-height: 40px;
}

.drawer-close-button {
  align-items: center;
  background: transparent;
  border: 0;
  color: #111827;
  cursor: pointer;
  display: inline-flex;
  font-size: 34px;
  height: 42px;
  justify-content: center;
  line-height: 1;
  width: 42px;
}

.drawer-close-button:hover {
  color: var(--oms-primary);
}

.worker-calendar {
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  max-width: 350px;
  padding: 12px;
}

.calendar-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}

.calendar-title {
  color: var(--oms-heading);
  font-size: 14px;
  font-weight: 760;
}

.calendar-nav {
  align-items: center;
  background: #fff;
  border: 1px solid var(--oms-border);
  border-radius: 6px;
  color: var(--oms-text);
  cursor: pointer;
  display: inline-flex;
  font-size: 18px;
  height: 26px;
  justify-content: center;
  width: 26px;
}

.calendar-weekdays,
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 34px);
  gap: 6px;
}

.calendar-weekdays {
  color: var(--oms-sub);
  font-size: 12px;
  margin-bottom: 6px;
  text-align: center;
}

.calendar-day {
  align-items: center;
  background: #fff;
  border: 1px solid var(--oms-border);
  border-radius: 6px;
  color: var(--oms-text);
  display: flex;
  font-size: 12px;
  height: 30px;
  justify-content: center;
}

.calendar-day.is-empty {
  background: transparent;
  border-color: transparent;
}

.calendar-day.has-record {
  background: var(--oms-primary);
  border-color: var(--oms-primary);
  color: #fff;
  font-weight: 760;
}

.detail-section {
  min-width: 0;
}

.section-title {
  color: var(--oms-heading);
  font-size: 16px;
  font-weight: 760;
}

.allocation-summary {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 14px;
}

.allocation-summary div {
  background: #f8fafc;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  padding: 10px 12px;
}

.allocation-summary span {
  color: var(--oms-sub);
  display: block;
  font-size: 12px;
  line-height: 18px;
}

.allocation-summary strong {
  color: var(--oms-heading);
  display: block;
  font-size: 16px;
  font-weight: 760;
  line-height: 24px;
}

.allocation-input {
  align-items: center;
  display: flex;
  gap: 8px;
}

.allocation-history-title {
  color: var(--oms-heading);
  font-size: 14px;
  font-weight: 760;
  margin: 14px 0 8px;
}

.allocation-history {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-height: 120px;
  overflow-y: auto;
}

.allocation-history-item {
  align-items: center;
  background: #f8fafc;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  padding: 9px 12px;
}

.allocation-history-item span {
  color: var(--oms-sub);
  min-width: 0;
}

.allocation-history-item strong {
  color: var(--oms-heading);
  font-weight: 760;
}

.worker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 220px));
  gap: 18px;
  padding: 18px;
  align-items: start;
}

.worker-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.worker-actions {
  display: flex;
  justify-content: center;
  gap: 18px;
  width: 100%;
}

.worker-bubble {
  width: 100%;
  border: 1px solid var(--oms-border);
  border-radius: 14px;
  background: #fff;
  aspect-ratio: 1.8 / 1;
  min-height: 104px;
  padding: 16px 18px;
  font-size: 22px;
  font-weight: 760;
  color: var(--oms-text);
  cursor: pointer;
  box-shadow: var(--oms-shadow);
  transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease, background .16s ease;
}

.worker-bubble:hover {
  background: var(--oms-primary-soft);
  border-color: var(--oms-primary-border);
  color: var(--oms-primary);
  box-shadow: 0 14px 30px rgba(15, 23, 42, .10);
  transform: translateY(-1px);
}

.suggestion-main {
  line-height: 20px;
}

.suggestion-sub {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
}

.entry-box {
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: center;
}

.entry-name {
  color: var(--oms-heading);
  font-size: 18px;
  font-weight: 760;
}

.entry-qr {
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  height: 220px;
  width: 220px;
}

@media (max-width: 900px) {
  .worker-grid {
    grid-template-columns: 1fr;
  }
}
</style>
