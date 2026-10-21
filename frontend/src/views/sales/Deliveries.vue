<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">发货管理</div>
      <div class="muted">从销售订单自动生成，维护每笔订单的分批发货记录和结算登记状态。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="订单号 / 客户公司 / 产品编号 / 产品名称" clearable />
        </div>
        <div class="filter-field">
          <div class="filter-label">发货状态</div>
          <el-select
            v-model="fulfillmentStatusFilters"
            multiple
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="1"
            clearable
            style="width: 220px"
            placeholder="全部状态"
            @change="handleFulfillmentStatusChange"
          >
            <el-option label="全部状态" value="all">
              <div class="status-filter-option"><el-checkbox :model-value="fulfillmentStatusFilters.length === 0" tabindex="-1" />全部状态</div>
            </el-option>
            <el-option v-for="status in fulfillmentStatusOptions" :key="status.value" :label="status.label" :value="status.value">
              <div class="status-filter-option"><el-checkbox :model-value="fulfillmentStatusFilters.includes(status.value)" tabindex="-1" />{{ status.label }}</div>
            </el-option>
          </el-select>
        </div>
        <div class="filter-field">
          <div class="filter-label">汇总筛选</div>
          <div class="left-actions">
            <el-button :type="filterMode === 'unshipped' ? 'primary' : 'default'" @click="toggleFilter('unshipped')">未送货完毕</el-button>
            <el-button :type="filterMode === 'overdue' ? 'primary' : 'default'" @click="toggleFilter('overdue')">逾期发货</el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="left-actions">
          <span class="table-count">点击发货维护分批记录，并查看对应对账和收款状态</span>
        </div>
        <div class="right-actions">
          <span class="table-count">共 {{ totalRows }} 条</span>
          <el-button @click="load()">刷新</el-button>
        </div>
      </div>
      <el-table :data="rows" height="calc(100vh - 270px)" border stripe @row-click="selectRow">
        <el-table-column type="index" :index="rowIndex" width="50" label="#" fixed />
        <el-table-column prop="orderNo" label="订单号" width="150" />
        <el-table-column prop="fulfillmentStatus" label="状态" width="130">
          <template #default="{ row }">
            <span class="fulfillment-tag" :class="statusTagClass(row.fulfillmentStatus)">{{ row.fulfillmentStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户公司" width="170" show-overflow-tooltip />
        <el-table-column prop="productCode" label="产品编号" width="150" />
        <el-table-column prop="productName" label="产品名称" width="150" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="150" />
        <el-table-column prop="returnedQty" label="退货数量" width="120" />
        <el-table-column prop="unit" label="单位" width="150" />
        <el-table-column prop="plannedDeliveryDate" label="计划交货日期" width="150" />
        <el-table-column prop="lastDeliveryDate" label="上一次发货" width="150">
          <template #default="{ row }">{{ row.lastDeliveryDate || '-' }}</template>
        </el-table-column>
        <el-table-column prop="unshippedQty" label="未发货数" width="150" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">发货</el-button>
            <el-button link type="danger" @click.stop="openReturn(row)">退货</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          :current-page="currentPage"
          :page-size="pageSize"
          :page-sizes="PAGE_SIZE_OPTIONS"
          :total="totalRows"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>

      <el-dialog v-model="returnVisible" title="退货登记" width="min(820px, 94vw)" destroy-on-close>
        <template v-if="returnRow">
          <p>{{ returnRow.orderNo }} · {{ returnRow.customerName }} · {{ returnRow.productName }} · {{ returnRow.productCode }}</p>
          <p class="muted">退货独立记录，不改变原发货量和发货状态。对账时作为负数量冲减金额。</p>
          <el-form label-width="100px">
            <el-form-item label="退货日期" required><el-date-picker v-model="returnForm.returnDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
            <el-form-item label="退货数量" required>
              <el-input-number v-model="returnForm.qty" :min="1" :max="Math.max(1, Number(returnRow.returnableQty || 0))" :precision="0" :step="1" step-strictly :controls="false" aria-label="退货数量" />
              <span class="muted" style="margin-left:12px">可退 {{ returnRow.returnableQty || 0 }} {{ returnRow.unit }}</span>
            </el-form-item>
            <el-form-item label="备注"><el-input v-model="returnForm.remark" maxlength="500" /></el-form-item>
          </el-form>
          <div class="panel-head"><span class="panel-title">退货记录</span></div>
          <el-table :data="returnHistory" border max-height="260">
            <el-table-column prop="record.returnDate" label="退货日期" width="120" />
            <el-table-column prop="record.qty" label="退货数量" width="110" />
            <el-table-column prop="record.unitPrice" label="单价" width="95" :formatter="priceColumn" />
            <el-table-column prop="record.amount" label="退货金额" width="110" :formatter="moneyColumn" />
            <el-table-column prop="reconciledQty" label="已对账数量" width="110" />
            <el-table-column prop="record.remark" label="备注" min-width="100" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-popconfirm v-if="Number(row.reconciledQty) === 0" title="确认删除这条未对账退货？" @confirm="deleteReturn(row.record.id)">
                  <template #reference><el-button link type="danger">删除</el-button></template>
                </el-popconfirm>
                <span v-else class="muted">已对账</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <template #footer>
          <el-button @click="returnVisible = false">关闭</el-button>
          <el-button type="primary" :loading="savingReturn" :disabled="!Number(returnRow?.returnableQty)" @click="saveReturn">保存退货</el-button>
        </template>
      </el-dialog>

      <el-drawer v-model="detailVisible" title="发货管理明细" size="68%" class="delivery-detail-drawer">
        <div v-if="selectedRow" class="detail-shell">
          <div class="summary-grid">
            <div><span>订单号</span><strong :title="selectedRow.orderNo">{{ selectedRow.orderNo }}</strong></div>
            <div><span>客户公司</span><strong :title="selectedRow.customerName || '-'">{{ selectedRow.customerName || '-' }}</strong></div>
            <div><span>产品编号</span><strong :title="selectedRow.productCode || '-'">{{ selectedRow.productCode || '-' }}</strong></div>
            <div><span>产品名称</span><strong :title="selectedRow.productName || '-'">{{ selectedRow.productName || '-' }}</strong></div>
            <div><span>数量</span><strong :title="String(selectedRow.qty ?? '-')">{{ selectedRow.qty }}</strong></div>
            <div><span>退货数量</span><strong>{{ selectedRow.returnedQty || 0 }}</strong></div>
            <div><span>单位</span><strong :title="selectedRow.unit || '-'">{{ selectedRow.unit || '-' }}</strong></div>
            <div>
              <span>状态</span>
              <strong><span class="fulfillment-tag" :class="statusTagClass(selectedRow.fulfillmentStatus)">{{ selectedRow.fulfillmentStatus }}</span></strong>
            </div>
            <div><span>计划交货日期</span><strong :title="selectedRow.plannedDeliveryDate || '-'">{{ selectedRow.plannedDeliveryDate || '-' }}</strong></div>
            <div><span>上一次发货</span><strong :title="selectedRow.lastDeliveryDate || '-'">{{ selectedRow.lastDeliveryDate || '-' }}</strong></div>
            <div><span>未发货数</span><strong :title="String(selectedRow.unshippedQty ?? '-')">{{ selectedRow.unshippedQty }}</strong></div>
          </div>

          <div class="info-grid">
            <section class="info-panel">
              <div class="panel-head">
                <div class="panel-title">发货信息</div>
                <el-button type="primary" size="small" @click="openDeliveryDialog">新增</el-button>
              </div>
              <el-empty v-if="deliveries.length === 0" description="暂无发货信息" />
              <div v-else class="info-list">
                <div
                  v-for="item in deliveries"
                  :key="item.id"
                  class="delivery-card"
                  :class="{ 'is-invoiced': deliveryRegistration(item.id) }"
                >
                  <div class="delivery-code">
                    <span class="seq">#{{ item.deliverySeq }}</span>
                  </div>
                  <div class="delivery-facts">
                    <div class="delivery-fact">
                      <span>发货量</span>
                      <strong>{{ item.deliveryQty }}</strong>
                    </div>
                    <div class="delivery-fact">
                      <span>发货方式</span>
                      <strong>{{ item.deliveryMethod || '-' }}</strong>
                    </div>
                    <div class="delivery-fact">
                      <span>对账状态</span>
                      <strong>{{ deliveryRegistration(item.id)?.statementNo || '待对账' }}</strong>
                    </div>
                  </div>
                  <div class="delivery-extra">
                    <div class="delivery-time">
                      <span>时间</span>
                      <strong>{{ item.deliveryDate || '-' }}</strong>
                    </div>
                    <div class="delivery-time">
                      <span>是否有质检报告</span>
                      <strong>{{ hasQualityReport(item) ? '是' : '否' }}</strong>
                    </div>
                  </div>
                  <div v-if="qualityReportsFor(item).length" class="report-line">
                    <span>质检报告：</span>
                    <el-button
                      v-for="report in qualityReportsFor(item)"
                      :key="report.id"
                      link
                      type="primary"
                      @click="downloadAttachment(report)"
                    >
                      {{ report.originalName }}
                    </el-button>
                  </div>
                  <div class="delivery-actions">
                    <el-button class="action-pill is-success" link @click="downloadDeliveryNote(item)">生成送货单</el-button>
                    <el-button class="action-pill is-warning" link @click="openEditDelivery(item)">修改</el-button>
                    <el-button
                      class="action-pill is-primary"
                      link
                      :disabled="!hasQualityReport(item)"
                      @click="viewQualityReport(item)"
                    >
                      查看质检报告
                    </el-button>
                    <el-popconfirm title="确认删除该发货信息？" @confirm="deleteDeliveryInfo(item)">
                      <template #reference><el-button class="action-pill is-danger" link>删除</el-button></template>
                    </el-popconfirm>
                  </div>
                </div>
              </div>
            </section>

            <section class="info-panel">
              <div class="panel-head">
                <div class="panel-title">登记信息</div>
                <span class="panel-note">来自对账单和收款登记</span>
              </div>
              <el-empty v-if="registrations.length === 0" description="暂无登记信息" class="compact-empty" />
              <div v-else class="info-list">
                <div v-for="item in registrations" :key="item.id" class="info-row">
                  <div class="seq">{{ methodText(item.settlementMethod) }}</div>
                  <div class="row-main">
                    <div class="row-title">{{ item.statementNo }} · {{ item.status }}</div>
                    <div class="row-meta">
                      <span>对账金额：{{ money(item.receivableAmount) }}</span>
                      <span>发票号：{{ item.invoiceNos || '-' }}</span>
                      <span>已到账：{{ money(item.receivedAmount) }}</span>
                      <span>剩余欠款：{{ money(item.arrearsAmount) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </div>
      </el-drawer>

      <el-dialog v-model="deliveryDialogVisible" :title="deliveryDialogMode === 'edit' ? '修改发货单' : '新增发货单'" width="1100px" top="5vh">
        <div class="delivery-dialog-customer">
          <span>收货客户</span>
          <strong>{{ selectedRow?.customerName || '-' }}</strong>
          <small>{{ quantityLocked ? '该发货已对账或已出库，产品和数量不可变更' : '同一张发货单仅可合并该客户的订单产品' }}</small>
        </div>
        <el-form :model="deliveryForm" label-width="90px">
          <el-row :gutter="12">
            <el-col :span="8"><el-form-item label="发货日期" required :error="deliveryErrors.deliveryDate"><el-date-picker v-model="deliveryForm.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="发货方式" required :error="deliveryErrors.deliveryMethod"><el-input v-model="deliveryForm.deliveryMethod" placeholder="物流、自提等" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="物流单号"><el-input v-model="deliveryForm.logisticsNo" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="发货信息"><el-input v-model="deliveryForm.deliveryInfo" placeholder="车辆、司机或其他附加信息" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="备注"><el-input v-model="deliveryForm.remark" /></el-form-item></el-col>
          </el-row>
        </el-form>

        <div class="batch-head">
          <div><strong>选择订单产品</strong><span>已选 {{ selectedDeliveryLines.length }} 项</span></div>
          <div class="batch-tools">
            <span>订单范围</span>
            <el-select v-model="deliveryOrderFilter" size="small" style="width: 220px">
              <el-option label="全部可发货订单" value="all" />
              <el-option v-for="order in deliveryOrderOptions" :key="order.value" :label="order.label" :value="order.value" />
            </el-select>
          </div>
        </div>
        <el-table :data="visibleDeliveryCandidates" border max-height="360">
          <el-table-column label="选择" width="70" align="center">
            <template #default="{ row }"><el-checkbox v-model="row.selected" :disabled="quantityLocked" @change="checked => toggleDeliveryLine(row, checked)" /></template>
          </el-table-column>
          <el-table-column prop="orderNo" label="订单号" width="150" />
          <el-table-column prop="productCode" label="产品编号" width="160" show-overflow-tooltip />
          <el-table-column prop="productName" label="产品名称" min-width="190" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column :prop="deliveryDialogMode === 'edit' ? 'maxDeliveryQty' : 'unshippedQty'" :label="deliveryDialogMode === 'edit' ? '可填上限' : '未发货数'" width="110" />
          <el-table-column label="本次发货量" width="170">
            <template #default="{ row }">
              <el-form-item :error="deliveryErrors[`line-${row.itemId}`]">
                <el-input-number v-model="row.deliveryQty" :disabled="!row.selected || quantityLocked" :min="0" :max="Number(row.maxDeliveryQty ?? row.unshippedQty ?? 0)" :step="1" :precision="0" controls-position="right" />
              </el-form-item>
            </template>
          </el-table-column>
          <el-table-column v-if="deliveryDialogMode === 'create'" label="质检报告" width="260">
            <template #default="{ row }">
              <el-upload
                class="quality-report-upload"
                :class="{ 'is-disabled': !row.selected, 'has-file': qualityReportFile(row) }"
                v-model:file-list="row.qualityReportFiles"
                action="#"
                :auto-upload="false"
                :limit="1"
                :disabled="!row.selected"
                :show-file-list="false"
                drag
                :on-change="(_file, files) => handleQualityReportChange(row, files)"
                :on-exceed="files => handleQualityReportExceed(row, files)"
                :on-remove="() => handleQualityReportRemove(row)"
              >
                <div class="quality-report-dropzone" :class="{ 'is-disabled': !row.selected, 'has-file': qualityReportFile(row) }">
                  <el-icon class="quality-report-icon"><UploadFilled /></el-icon>
                  <div class="quality-report-copy">
                    <span class="quality-report-name">
                      {{ qualityReportFile(row)?.name || (row.selected ? '拖拽文件到此处' : '勾选产品后上传') }}
                    </span>
                    <small v-if="row.selected">{{ qualityReportFile(row) ? '点击可重新选择' : '或点击选择文件' }}</small>
                  </div>
                  <button
                    v-if="qualityReportFile(row)"
                    class="quality-report-remove"
                    type="button"
                    aria-label="移除质检报告"
                    title="移除文件"
                    @click.stop="handleQualityReportRemove(row)"
                  >
                    <el-icon><Close /></el-icon>
                  </button>
                </div>
              </el-upload>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="filteredDeliveryCandidates.length > deliveryCandidatePageSize"
          v-model:current-page="deliveryCandidatePage"
          :page-size="deliveryCandidatePageSize"
          :total="filteredDeliveryCandidates.length"
          layout="total, prev, pager, next"
          class="candidate-pagination"
        />
        <template #footer>
          <el-button @click="deliveryDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="savingDelivery" @click="saveDelivery">{{ deliveryDialogMode === 'edit' ? '保存修改' : '保存并生成送货单' }}</el-button>
        </template>
      </el-dialog>

    </div>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import { formatMoney as money, moneyColumn, priceColumn } from '@/format/money'
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, UploadFilled } from '@element-plus/icons-vue'
import { createLatestRequest } from '@/api/latestRequest'
import { getAction, postAction, remove, type PageResponse } from '@/api/crud'
import http from '@/api/http'
import { usePageAutoRefresh } from '@/composables/usePageAutoRefresh'

const rows = shallowRef<any[]>([])
const totalRows = ref(0)
const returnVisible = ref(false)
const savingReturn = ref(false)
const returnRow = ref<any>(null)
const returnHistory = ref<any[]>([])
const returnForm = reactive<any>({})

async function refreshReturns() {
  await load()
  returnRow.value = rows.value.find(row => row.itemId === returnRow.value.itemId) || returnRow.value
  const result: any = await getAction(`/sales/deliveries/invoice-board/${returnRow.value.id}?itemId=${returnRow.value.itemId}`)
  returnHistory.value = result.returns || []
}

async function openReturn(row: any) {
  returnRow.value = row
  returnHistory.value = []
  const now = new Date()
  Object.assign(returnForm, { qty: undefined, returnDate: new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10), remark: '' })
  await refreshReturns()
  returnVisible.value = true
}

async function saveReturn() {
  if (savingReturn.value) return
  if (!returnForm.returnDate) return ElMessage.error('请选择退货日期')
  const qty = Number(returnForm.qty)
  if (!Number.isInteger(qty) || qty <= 0) return ElMessage.error('退货数量必须为正整数')
  if (qty > Number(returnRow.value.returnableQty)) return ElMessage.error('不能超过已发货未退货数量')
  savingReturn.value = true
  try {
    await postAction(`/sales/deliveries/invoice-board/${returnRow.value.id}/returns`, { ...returnForm, qty, itemId: returnRow.value.itemId })
    ElMessage.success('退货已登记，可在客户对账中选择')
    returnForm.qty = undefined
    await refreshReturns()
  } finally { savingReturn.value = false }
}

async function deleteReturn(id: number) {
  await remove('/sales/deliveries/returns', id)
  ElMessage.success('退货记录已删除')
  await refreshReturns()
}
const keyword = ref('')
const filterMode = ref<'all' | 'unshipped' | 'overdue'>('all')
const fulfillmentStatusOptions = [
  { label: '未发货', value: '未发货' },
  { label: '部分发货', value: '部分发货' },
  { label: '已发送', value: '已发送' },
  { label: '逾期未发货', value: '逾期未发货' },
  { label: '逾期部分发货', value: '逾期部分发货' },
  { label: '逾期发货', value: 'overdue' }
]
const fulfillmentStatusFilters = ref<string[]>([])
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const detailVisible = ref(false)
const deliveryDialogVisible = ref(false)
const selectedRow = ref<any>(null)
const detail = ref<any>({})
const deliveryForm = reactive<any>({})
const deliveryCandidates = ref<any[]>([])
const deliveryOrderFilter = ref('all')
const deliveryCandidatePage = ref(1)
const deliveryCandidatePageSize = DEFAULT_PAGE_SIZE
const savingDelivery = ref(false)
const deliveryErrors = reactive<Record<string, string>>({})
const deliveryDialogMode = ref<'create' | 'edit'>('create')
const editingDeliveryId = ref<number | null>(null)
const quantityLocked = ref(false)

const deliveries = computed(() => detail.value.deliveries || [])
const registrations = computed(() => detail.value.registrations || [])
const selectedDeliveryLines = computed(() => deliveryCandidates.value.filter(item => item.selected))
const deliveryOrderOptions = computed(() => {
  const seen = new Map<string, any>()
  deliveryCandidates.value.forEach(row => {
    const value = String(row.orderId || row.orderNo || '')
    if (value && !seen.has(value)) seen.set(value, { value, label: row.orderNo || `订单 ${value}` })
  })
  return [...seen.values()]
})
const filteredDeliveryCandidates = computed(() => deliveryOrderFilter.value === 'all'
  ? deliveryCandidates.value
  : deliveryCandidates.value.filter(row => String(row.orderId || row.orderNo || '') === deliveryOrderFilter.value))
const visibleDeliveryCandidates = computed(() => {
  const start = (deliveryCandidatePage.value - 1) * deliveryCandidatePageSize
  return filteredDeliveryCandidates.value.slice(start, start + deliveryCandidatePageSize)
})

function showSelectedCandidatePage() {
  const selectedIndex = filteredDeliveryCandidates.value.findIndex(row => row.selected)
  deliveryCandidatePage.value = selectedIndex < 0
    ? 1
    : Math.floor(selectedIndex / deliveryCandidatePageSize) + 1
}

function rowIndex(index: number) {
  return (currentPage.value - 1) * pageSize.value + index + 1
}

function handleFulfillmentStatusChange(statuses: string[]) {
  if (statuses.includes('all')) fulfillmentStatusFilters.value = []
}

async function load(targetPage = currentPage.value, targetPageSize = pageSize.value) {
  const params = new URLSearchParams({
    page: String(targetPage - 1),
    size: String(targetPageSize),
    keyword: keyword.value.trim(),
    filterMode: filterMode.value,
    fulfillmentStatus: fulfillmentStatusFilters.value.join(',') || 'all'
  })
  const result = await pageRequest.run(signal => getAction<PageResponse<any>>(`/sales/deliveries/invoice-board/page?${params}`, { signal }))
  if (!result) return
  const lastPage = Math.max(1, result.totalPages)
  if (targetPage > lastPage) {
    await load(lastPage, targetPageSize)
    return
  }
  rows.value = result.items
  totalRows.value = result.total
  currentPage.value = targetPage
  pageSize.value = targetPageSize
}

function changePage(page: number) { void load(page, pageSize.value) }
function changePageSize(size: number) { void load(1, size) }

async function openDetail(row: any) {
  selectedRow.value = row
  detailVisible.value = true
  await Promise.all([loadDetail(row.id, row.itemId), loadCustomerCandidates(row.customerId)])
}

async function selectRow(row: any) {
  selectedRow.value = row
}

async function loadDetail(orderId: number, itemId?: number) {
  const query = itemId == null ? '' : `?itemId=${itemId}`
  detail.value = await getAction(`/sales/deliveries/invoice-board/${orderId}${query}`)
}

const deliveryCandidateCache = new Map<number, { expiresAt: number, rows: any[] }>()
async function loadCustomerCandidates(customerId?: number, force = false, deliveryId?: number) {
  if (!customerId) return []
  const cached = deliveryCandidateCache.get(customerId)
  if (!deliveryId && !force && cached && cached.expiresAt > Date.now()) return cached.rows
  const params = new URLSearchParams({ customerId: String(customerId) })
  if (deliveryId) params.set('deliveryId', String(deliveryId))
  const result = await getAction<any[]>(`/sales/deliveries/invoice-board/candidates?${params}`)
  if (!deliveryId) deliveryCandidateCache.set(customerId, { expiresAt: Date.now() + 30_000, rows: result })
  return result
}

async function openDeliveryDialog() {
  deliveryDialogMode.value = 'create'
  editingDeliveryId.value = null
  quantityLocked.value = false
  Object.keys(deliveryForm).forEach(key => delete deliveryForm[key])
  Object.keys(deliveryErrors).forEach(key => delete deliveryErrors[key])
  deliveryOrderFilter.value = 'all'
  deliveryForm.deliveryDate = new Date().toISOString().slice(0, 10)
  deliveryForm.deliveryMethod = ''
  deliveryForm.logisticsNo = ''
  deliveryForm.deliveryInfo = ''
  deliveryForm.remark = ''
  const candidates = await loadCustomerCandidates(selectedRow.value?.customerId)
  deliveryCandidates.value = candidates
    .map(row => ({
      ...row,
      selected: row.itemId === selectedRow.value?.itemId,
      deliveryQty: row.itemId === selectedRow.value?.itemId ? Number(row.unshippedQty || 0) : 0,
      maxDeliveryQty: Number(row.unshippedQty || 0),
      qualityReportFiles: []
    }))
  showSelectedCandidatePage()
  deliveryDialogVisible.value = true
}

async function openEditDelivery(item: any) {
  const savedDetail: any = await getAction(`/sales/deliveries/${item.id}/detail`)
  const saved = savedDetail.delivery || {}
  const currentItems = savedDetail.items || []
  const currentQty = new Map<string, number>(currentItems.map((line: any) => [String(line.orderItemId), Number(line.qty || 0)]))
  deliveryDialogMode.value = 'edit'
  editingDeliveryId.value = item.id
  quantityLocked.value = Boolean(savedDetail.quantityLocked)
  Object.keys(deliveryForm).forEach(key => delete deliveryForm[key])
  Object.keys(deliveryErrors).forEach(key => delete deliveryErrors[key])
  Object.assign(deliveryForm, {
    deliveryDate: saved.deliveryDate,
    deliveryMethod: saved.deliveryMethod || '',
    logisticsNo: saved.logisticsNo || '',
    deliveryInfo: saved.deliveryInfo || '',
    remark: saved.remark || '',
    printPrice: saved.printPrice
  })
  deliveryOrderFilter.value = 'all'
  const customerId = Number(saved.customerId || selectedRow.value?.customerId)
  const candidates = await loadCustomerCandidates(customerId, true, item.id)
  deliveryCandidates.value = candidates
    .filter(row => {
      const originalQty = currentQty.get(String(row.itemId)) || 0
      return originalQty > 0 || (!quantityLocked.value && Number(row.unshippedQty || 0) > 0)
    })
    .map(row => {
      const originalQty = currentQty.get(String(row.itemId)) || 0
      return {
        ...row,
        selected: originalQty > 0,
        deliveryQty: originalQty,
        originalQty,
        maxDeliveryQty: Number(row.unshippedQty || 0) + originalQty,
        qualityReportFiles: []
      }
    })
  showSelectedCandidatePage()
  deliveryDialogVisible.value = true
}

function toggleDeliveryLine(row: any, checked: boolean | string | number) {
  row.deliveryQty = checked ? (Number(row.deliveryQty) > 0 ? row.deliveryQty : Number(row.maxDeliveryQty ?? row.unshippedQty ?? 0)) : 0
}

async function saveDelivery() {
  if (!selectedRow.value) return
  Object.keys(deliveryErrors).forEach(key => delete deliveryErrors[key])
  if (!deliveryForm.deliveryDate) deliveryErrors.deliveryDate = '发货日期未填写'
  if (!String(deliveryForm.deliveryMethod || '').trim()) deliveryErrors.deliveryMethod = '物流方式未填写'
  if (deliveryErrors.deliveryDate || deliveryErrors.deliveryMethod) {
    ElMessage.error(deliveryErrors.deliveryDate || deliveryErrors.deliveryMethod)
    return
  }
  const lines = selectedDeliveryLines.value
  if (!lines.length) return ElMessage.error('请至少选择一个订单产品')
  for (const line of lines) {
    const qty = Number(line.deliveryQty)
    if (!Number.isFinite(qty) || qty <= 0) { deliveryErrors[`line-${line.itemId}`] = '本次发货量未填写'; ElMessage.error(`请填写“${line.productName}”的本次发货量`); return }
    if (!Number.isInteger(qty)) { deliveryErrors[`line-${line.itemId}`] = '发货量必须为整数'; ElMessage.error(`“${line.productName}”的本次发货量必须为整数`); return }
    if (qty > Number(line.maxDeliveryQty ?? line.unshippedQty ?? 0)) { deliveryErrors[`line-${line.itemId}`] = '发货量超过可填上限'; ElMessage.error(`“${line.productName}”的发货量超过可填上限`); return }
  }
  savingDelivery.value = true
  try {
    const payload = {
      ...deliveryForm,
      items: lines.map(line => ({ orderItemId: line.itemId, qty: line.deliveryQty }))
    }
    if (deliveryDialogMode.value === 'edit' && editingDeliveryId.value != null) {
      await http.put(`/sales/deliveries/invoice-board/deliveries/${editingDeliveryId.value}`, payload)
      ElMessage.success('发货信息已修改')
      deliveryDialogVisible.value = false
      await load()
      const freshSelected = rows.value.find(row => row.itemId === selectedRow.value?.itemId)
      if (freshSelected) selectedRow.value = freshSelected
      await refreshSelected()
      return
    }
    const saved = await postAction('/sales/deliveries/batch', payload)
    const savedDetail = await getAction<any>(`/sales/deliveries/${saved.id}/detail`)
    const uploadResult = await uploadQualityReports(lines, savedDetail.items || [])
    ElMessage.success('发货单已生成')
    if (uploadResult.attempted > 0 && uploadResult.failures.length === 0) {
      ElMessage.success(uploadResult.attempted === 1 ? '质检报告上传成功' : `质检报告上传成功（${uploadResult.attempted}份）`)
    } else if (uploadResult.failures.length > 0) {
      const detail = uploadResult.failures
        .map(item => `${item.name}：${item.message}`)
        .join('；')
      const prefix = uploadResult.succeeded > 0 ? '部分质检报告上传失败' : '质检报告上传失败'
      ElMessage.error(`${prefix}：${detail}`)
    }
    deliveryDialogVisible.value = false
    deliveryCandidateCache.delete(selectedRow.value?.customerId)
    await load()
    const freshSelected = rows.value.find(row => row.itemId === selectedRow.value?.itemId)
    if (freshSelected) selectedRow.value = freshSelected
    await refreshSelected()
    downloadDeliveryNote(saved)
  } finally {
    savingDelivery.value = false
  }
}

async function deleteDeliveryInfo(item: any) {
  await remove('/sales/deliveries/invoice-board/deliveries', item.id)
  ElMessage.success('发货信息已删除')
  const selectedItemId = selectedRow.value?.itemId
  deliveryCandidateCache.delete(selectedRow.value?.customerId)
  await load()
  const freshSelected = rows.value.find(row => row.itemId === selectedItemId)
  if (freshSelected) selectedRow.value = freshSelected
  await refreshSelected()
}

async function refreshSelected() {
  if (!selectedRow.value) return
  await loadDetail(selectedRow.value.id, selectedRow.value.itemId)
  const item = (detail.value.items || []).find((candidate: any) => candidate.id === selectedRow.value.itemId)
  const summary = detail.value.summary || {}
  const index = rows.value.findIndex(row => row.id === selectedRow.value.id && row.itemId === selectedRow.value.itemId)
  if (index < 0 || !item) return
  const fresh = {
    ...rows.value[index],
    unshippedQty: summary.unshippedQty ?? Math.max(0, Number(item.qty || 0) - Number(item.shippedQty || 0)),
    plannedDeliveryDate: summary.plannedDeliveryDate,
    lastDeliveryDate: summary.lastDeliveryDate,
    fulfillmentStatus: summary.fulfillmentStatus
  }
  rows.value = rows.value.map((row, rowIndex) => rowIndex === index ? fresh : row)
  selectedRow.value = fresh
}

function toggleFilter(mode: 'unshipped' | 'overdue') {
  filterMode.value = filterMode.value === mode ? 'all' : mode
}

function statusTagClass(status?: string) {
  if (status === '部分发货') return 'is-partial'
  if (status === '已发送') return 'is-delivered'
  if (status === '逾期未发货') return 'is-overdue-unshipped'
  if (status === '逾期部分发货') return 'is-overdue-partial'
  return 'is-unshipped'
}

function deliveryRegistration(deliveryId: number) {
  return registrations.value.find((item: any) => (item.deliveryIds || []).includes(deliveryId))
}

function methodText(method?: string) {
  if (method === 'CASH') return '现汇'
  if (method === 'INVOICE') return '开票'
  return '未登记'
}

function qualityReportFile(row: any) {
  return row.qualityReportFiles?.[0]
}

function handleQualityReportChange(row: any, files: any[]) {
  row.qualityReportFiles = files.slice(-1)
}

function handleQualityReportExceed(row: any, files: File[]) {
  row.qualityReportFiles = files.slice(0, 1).map(file => ({ name: file.name, raw: file }))
}

function handleQualityReportRemove(row: any) {
  row.qualityReportFiles = []
}

async function uploadQualityReports(lines: any[], savedItems: any[]) {
  const itemByOrderItemId = new Map(savedItems.map((item: any) => [String(item.orderItemId), item]))
  const uploadLines = lines
    .filter(line => line.qualityReportFiles?.[0])
    .map(line => ({ line, deliveryItem: itemByOrderItemId.get(String(line.itemId)) }))
  if (!uploadLines.length) return { attempted: 0, succeeded: 0, failures: [] }
  const results = await Promise.all(uploadLines.map(({ line, deliveryItem }) => {
    if (!deliveryItem?.id) {
      return {
        name: line.qualityReportFiles[0]?.name || '未命名文件',
        ok: false,
        message: '未找到对应的发货明细'
      }
    }
    return uploadQualityReport(line, deliveryItem.id)
  }))
  return {
    attempted: results.length,
    succeeded: results.filter(result => result.ok).length,
    failures: results.filter(result => !result.ok)
  }
}

async function uploadQualityReport(line: any, deliveryItemId: number) {
  const uploadFile = line.qualityReportFiles?.[0]
  const file = uploadFile?.raw || uploadFile
  const name = uploadFile?.name || file?.name || '未命名文件'
  if (!file) return { name, ok: false, message: '未读取到文件内容' }
  const formData = new FormData()
  formData.append('file', file)
  formData.append('businessType', 'DELIVERY_ITEM_QUALITY_REPORT')
  formData.append('businessId', String(deliveryItemId))
  try {
    const attachment = await http.post<any, any>('/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      skipErrorToast: true
    })
    if (!attachment?.id) throw new Error('服务器未返回附件记录')
    return { name, ok: true, message: '' }
  } catch (error) {
    const message = error instanceof Error ? error.message : '网络或服务器异常'
    return { name, ok: false, message: message || '网络或服务器异常' }
  }
}

function qualityReportsFor(delivery: any) {
  return detail.value.qualityReports?.[delivery.deliveryItemId] || detail.value.qualityReports?.[delivery.id] || []
}

function hasQualityReport(delivery: any) {
  return qualityReportsFor(delivery).length > 0
}

async function viewQualityReport(item: any) {
  const report = qualityReportsFor(item)[0]
  if (!report) return
  await downloadAttachment(report)
}

async function downloadAttachment(report: any) {
  const blob = await http.get<Blob, Blob>(`/files/download/${report.id}`, { responseType: 'blob' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = report.originalName || '质检报告'
  link.click()
  URL.revokeObjectURL(url)
}

async function downloadDeliveryNote(row: any) {
  const blob = await http.get<Blob, Blob>(`/sales/deliveries/${row.id}/export`, { responseType: 'blob' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${row.deliveryNo || '送货单'}.xlsx`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(load)
usePageAutoRefresh(load, () => detailVisible.value || deliveryDialogVisible.value)
const pageRequest = createLatestRequest()
let searchTimer: ReturnType<typeof window.setTimeout> | undefined
function scheduleLoad() {
  if (searchTimer) window.clearTimeout(searchTimer)
  pageRequest.cancel()
  searchTimer = window.setTimeout(() => { void load(1, pageSize.value) }, 250)
}

watch([keyword, filterMode, fulfillmentStatusFilters], scheduleLoad)
watch(deliveryOrderFilter, () => { deliveryCandidatePage.value = 1 })
onBeforeUnmount(() => { if (searchTimer) window.clearTimeout(searchTimer); pageRequest.cancel() })
</script>

<style scoped>
.delivery-dialog-customer {
  align-items: baseline;
  background: #f6f8fb;
  border: 1px solid var(--oms-border);
  border-radius: 6px;
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  padding: 12px 14px;
}

.status-filter-option {
  align-items: center;
  display: flex;
  gap: 10px;
}

.status-filter-option :deep(.el-checkbox) {
  pointer-events: none;
}

.delivery-dialog-customer span,
.delivery-dialog-customer small {
  color: var(--oms-sub);
  font-size: 12px;
}

.delivery-dialog-customer strong {
  color: var(--oms-heading);
  font-size: 16px;
}

.delivery-dialog-customer small {
  margin-left: auto;
}

.batch-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin: 2px 0 10px;
}

.batch-head div {
  align-items: baseline;
  display: flex;
  gap: 10px;
}

.batch-head strong {
  color: var(--oms-heading);
  font-size: 15px;
}

.batch-head span {
  color: var(--oms-sub);
  font-size: 12px;
}

.batch-tools {
  align-items: center;
  display: flex;
  gap: 8px;
}

.batch-tools span {
  white-space: nowrap;
}

.candidate-pagination {
  justify-content: flex-end;
  margin-top: 12px;
}

.quality-report-upload {
  display: block;
  width: 100%;
}

.quality-report-upload :deep(.el-upload) {
  display: block;
  width: 100%;
}

.quality-report-upload :deep(.el-upload-dragger) {
  background: #fafcff;
  border: 1px dashed #aebdce;
  border-radius: 6px;
  height: 68px;
  padding: 0;
  transition: background-color 0.18s ease, border-color 0.18s ease;
  width: 100%;
}

.quality-report-upload:not(.is-disabled) :deep(.el-upload-dragger:hover),
.quality-report-upload.has-file :deep(.el-upload-dragger) {
  background: #f4f8ff;
  border-color: var(--oms-primary);
}

.quality-report-upload.is-disabled :deep(.el-upload-dragger) {
  background: #f5f6f8;
  border-color: #d5dae1;
  cursor: not-allowed;
}

.quality-report-dropzone {
  align-items: center;
  display: flex;
  gap: 8px;
  height: 100%;
  min-width: 0;
  padding: 8px 10px;
  text-align: left;
}

.quality-report-icon {
  color: var(--oms-primary);
  flex: 0 0 auto;
  font-size: 20px;
}

.quality-report-dropzone.is-disabled .quality-report-icon {
  color: #a8afb8;
}

.quality-report-copy {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-width: 0;
}

.quality-report-name {
  color: var(--oms-heading);
  display: block;
  font-size: 12px;
  line-height: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quality-report-copy small {
  color: var(--oms-sub);
  font-size: 11px;
  line-height: 16px;
}

.quality-report-dropzone.is-disabled .quality-report-name {
  color: #979ea8;
}

.quality-report-remove {
  align-items: center;
  background: transparent;
  border: 0;
  color: #7b8490;
  cursor: pointer;
  display: inline-flex;
  flex: 0 0 24px;
  height: 24px;
  justify-content: center;
  padding: 0;
  width: 24px;
}

.quality-report-remove:hover {
  color: #d94848;
}

.quality-form {
  margin-top: 14px;
}

.detail-shell {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-grid > div {
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  min-width: 0;
  padding: 10px 12px;
}

.summary-grid span {
  color: var(--oms-sub);
  display: block;
  font-size: 12px;
  margin-bottom: 6px;
}

.summary-grid strong {
  color: var(--oms-heading);
  display: block;
  font-size: 15px;
  font-weight: 760;
  line-height: 22px;
  overflow-wrap: anywhere;
  white-space: normal;
}

.fulfillment-tag {
  align-items: center;
  border: 1px solid #d5d9df;
  border-radius: 4px;
  color: #606773;
  display: inline-flex;
  font-size: 12px;
  font-weight: 700;
  justify-content: center;
  line-height: 24px;
  min-height: 24px;
  padding: 0 8px;
  white-space: nowrap;
}

.fulfillment-tag.is-unshipped {
  background: #f1f3f5;
}

.fulfillment-tag.is-partial {
  background: #fff8db;
  border-color: #ecd777;
  color: #8a6500;
}

.fulfillment-tag.is-delivered {
  background: #e9f7ee;
  border-color: #9bd4ad;
  color: #257a45;
}

.fulfillment-tag.is-overdue-unshipped {
  background: #ffeded;
  border-color: #f2a4a4;
  color: #c93434;
}

.fulfillment-tag.is-overdue-partial {
  background: #fff0df;
  border-color: #f1b97d;
  color: #b85c12;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.info-panel {
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  min-height: 300px;
  overflow: hidden;
  padding: 0;
}

.panel-head {
  align-items: center;
  border-bottom: 1px solid var(--oms-border);
  display: flex;
  justify-content: space-between;
  min-height: 52px;
  padding: 0 14px;
}

.panel-title {
  color: var(--oms-heading);
  font-size: 15px;
  font-weight: 720;
}

.panel-note {
  color: var(--oms-sub);
  font-size: 12px;
}

.info-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
}

.info-row {
  align-items: center;
  background: #f8fbff;
  border: 1px solid var(--oms-primary-border);
  border-radius: 14px;
  display: grid;
  gap: 12px;
  grid-template-columns: 54px minmax(0, 1fr);
  min-height: 72px;
  padding: 12px 14px;
}

.seq {
  background: #fff;
  border: 1px solid var(--oms-primary-border);
  border-radius: 999px;
  color: var(--oms-primary);
  display: inline-flex;
  font-size: 12px;
  font-weight: 760;
  justify-content: center;
  line-height: 24px;
  min-width: 36px;
  padding: 0 8px;
}

.row-main {
  min-width: 0;
}

.row-title {
  color: var(--oms-heading);
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  margin-bottom: 3px;
}

.row-meta {
  color: var(--oms-sub);
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 6px 12px;
  line-height: 18px;
}

.row-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
}

.delivery-card {
  background: #f8fbff;
  border: 1px solid var(--oms-primary-border);
  border-radius: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.delivery-card.is-invoiced {
  background: #315f9f;
  border-color: #4f7fc0;
  box-shadow: 0 12px 28px rgba(49, 95, 159, .16);
}

.delivery-card.is-invoiced .seq {
  background: rgba(255, 255, 255, .12);
  border-color: rgba(255, 255, 255, .32);
  color: #fff;
}

.delivery-card.is-invoiced .delivery-fact span,
.delivery-card.is-invoiced .delivery-time span,
.delivery-card.is-invoiced .report-line {
  color: rgba(255, 255, 255, .72);
}

.delivery-card.is-invoiced .delivery-fact strong,
.delivery-card.is-invoiced .delivery-time strong {
  color: #fff;
}

.delivery-card.is-invoiced .delivery-actions {
  border-top-color: rgba(255, 255, 255, .18);
}

.delivery-code {
  display: flex;
}

.delivery-facts {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.delivery-fact,
.delivery-time {
  min-width: 0;
}

.delivery-extra {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.delivery-fact span,
.delivery-time span {
  color: var(--oms-sub);
  display: block;
  font-size: 12px;
  line-height: 18px;
  margin-bottom: 2px;
}

.delivery-fact strong,
.delivery-time strong {
  color: var(--oms-heading);
  display: block;
  font-size: 14px;
  font-weight: 760;
  line-height: 20px;
  overflow-wrap: anywhere;
}

.delivery-actions {
  align-items: center;
  border-top: 1px solid var(--oms-border);
  display: flex;
  gap: 8px;
  padding-top: 12px;
}

.delivery-actions :deep(.el-button) {
  margin-left: 0;
}

.delivery-actions :deep(.el-button.is-disabled),
.delivery-actions :deep(.el-button.is-disabled:hover) {
  background: #f3f4f6;
  color: #9ca3af;
  cursor: not-allowed;
}

.action-pill {
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  height: 26px;
  line-height: 26px;
  padding: 0 10px !important;
}

.action-pill.is-success {
  background: #eff8e8;
  color: #4c9a2a;
}

.action-pill.is-warning {
  background: #fdf6ec;
  color: #b88230;
}

.action-pill.is-primary {
  background: var(--oms-primary-soft);
  color: var(--oms-primary);
}

.action-pill.is-danger {
  background: #fff1f2;
  color: #e5484d;
}

.report-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  color: var(--oms-sub);
  font-size: 12px;
}

.compact-empty {
  padding-top: 40px;
}

@media (max-width: 980px) {
  :deep(.el-dialog .el-col-8),
  :deep(.el-dialog .el-col-12) {
    flex: 0 0 100%;
    max-width: 100%;
  }

  .delivery-dialog-customer {
    align-items: flex-start;
    flex-direction: column;
  }

  .delivery-dialog-customer small {
    margin-left: 0;
  }

  .batch-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .batch-tools {
    align-items: flex-start;
    flex-direction: column;
  }

  .summary-grid,
  .info-grid {
    grid-template-columns: 1fr;
  }

  .info-row {
    grid-template-columns: 46px minmax(0, 1fr);
  }

  .row-actions {
    grid-column: 2;
    justify-content: flex-start;
  }

  .delivery-facts {
    grid-template-columns: 1fr;
  }

  .delivery-extra {
    grid-template-columns: 1fr;
  }
}
</style>
