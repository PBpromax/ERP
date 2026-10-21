<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">销售订单</div>
      <div class="muted">一张订单可包含多种产品，各产品独立进入发货管理。</div>
    </div>

    <div class="filter-card">
      <div class="filter-grid order-filter-grid">
        <el-form-item label="快速筛选">
          <el-input v-model="keyword" placeholder="订单号、客户公司、产品编号、产品名称" clearable />
        </el-form-item>
        <el-form-item label="发货状态">
          <el-select
            v-model="deliveryStatusFilters"
            multiple
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="1"
            clearable
            style="width: 100%"
            placeholder="全部状态"
            @change="handleDeliveryStatusChange"
          >
            <el-option label="全部状态" value="all">
              <div class="status-filter-option"><el-checkbox :model-value="deliveryStatusFilters.length === 0" tabindex="-1" />全部状态</div>
            </el-option>
            <el-option v-for="status in deliveryStatusOptions" :key="status" :label="status" :value="status">
              <div class="status-filter-option"><el-checkbox :model-value="deliveryStatusFilters.includes(status)" tabindex="-1" />{{ status }}</div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="年份">
          <el-select v-model="yearFilter" style="width: 100%">
            <el-option label="全部年份" value="all" />
            <el-option v-for="year in yearOptions" :key="year" :label="`${year}年`" :value="String(year)" />
          </el-select>
        </el-form-item>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="left-actions"><el-button v-if="canReadOrderPrices" type="primary" @click="openCreate">新增</el-button></div>
        <div class="right-actions"><el-button @click="load()">刷新</el-button></div>
      </div>
      <el-table class="order-list-table" :data="displayRows" :span-method="orderSpanMethod" height="calc(100vh - 270px)" border stripe>
        <el-table-column type="index" :index="rowIndex" width="50" label="#" fixed />
        <el-table-column prop="orderNo" label="订单号" width="150" fixed />
        <el-table-column prop="orderDate" label="订单日期" width="120" />
        <el-table-column prop="customerName" label="客户公司" width="170" show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="110" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="productCode" label="产品编号" min-width="200" show-overflow-tooltip />
        <el-table-column prop="units" label="单位" width="90" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="100" />
        <el-table-column v-if="canReadOrderPrices" prop="unitPriceText" label="单价" width="100" :formatter="priceColumn" />
        <el-table-column v-if="canReadOrderPrices" prop="amount" label="总价" width="120" :formatter="moneyColumn" />
        <el-table-column prop="plannedDeliveryDateText" label="计划交货日期" width="150" show-overflow-tooltip />
        <el-table-column prop="actualDeliveryDate" label="实际交货日期" width="140">
          <template #default="{ row }">{{ row.actualDeliveryDate || '-' }}</template>
        </el-table-column>
        <el-table-column prop="deliveryStatus" label="状态" width="110">
          <template #default="{ row }"><el-tag :type="deliveryStatusType(row.deliveryStatus)">{{ row.deliveryStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <div class="order-actions">
              <el-button link type="primary" @click="openEdit(row)">{{ canReadOrderPrices ? '编辑' : '详情' }}</el-button>
              <el-button link type="success" @click="downloadOrder(row)">{{ canReadOrderPrices ? '生成订单' : '生成无价订单' }}</el-button>
              <el-popconfirm v-if="canReadOrderPrices" title="确认删除该订单？" @confirm="deleteOrder(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination :current-page="currentPage" :page-size="pageSize" :page-sizes="PAGE_SIZE_OPTIONS"
          :total="totalRows" layout="total, sizes, prev, pager, next, jumper"
          @current-change="changePage" @size-change="changePageSize" />
      </div>

      <el-dialog v-model="dialogVisible" :title="form.id ? (canReadOrderPrices ? '编辑销售订单' : '销售订单详情') : '新增销售订单'" width="min(1180px, calc(100vw - 48px))" top="5vh">
        <el-form :disabled="!canReadOrderPrices" :model="form" label-width="100px">
          <el-row :gutter="12">
            <el-col :span="6"><el-form-item label="订单日期" required :error="formErrors.orderDate"><el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
            <el-col :span="6"><el-form-item label="订单号" required :error="formErrors.orderNo"><el-input v-model="form.orderNo" /></el-form-item></el-col>
            <el-col :span="6">
              <el-form-item label="客户公司" required :error="formErrors.customerName">
                <el-autocomplete v-model="form.customerName" :fetch-suggestions="customerSuggestions" value-key="value" clearable style="width:100%" @input="onCustomerInput" @select="selectCustomer">
                  <template #default="{ item }"><div class="suggestion-main" v-html="highlight(item.value, form.customerName)"></div><div v-if="item.sub" class="suggestion-sub">{{ item.sub }}</div></template>
                </el-autocomplete>
              </el-form-item>
            </el-col>
            <el-col :span="6"><el-form-item label="审核人"><el-input v-model="form.auditor" /></el-form-item></el-col>
            <el-col :span="6">
              <el-form-item label="联系人">
                <el-select v-model="form.contactPerson" filterable allow-create clearable style="width:100%" @change="selectContact">
                  <el-option v-for="contact in selectedCustomerContacts" :key="contact.id || contact.name"
                    :label="contact.position ? `${contact.name}（${contact.position}）` : contact.name" :value="contact.name" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="6"><el-form-item label="电话"><el-input v-model="form.customerPhone" /></el-form-item></el-col>
            <el-col :span="6"><el-form-item label="邮箱"><el-input v-model="form.customerEmail" /></el-form-item></el-col>
            <el-col :span="16"><el-form-item label="地址"><el-input v-model="form.customerAddress" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item></el-col>
          </el-row>
        </el-form>

        <el-alert v-if="validationErrors.length" class="validation-alert" type="error" :closable="false" show-icon>
          <template #title>请完成以下内容后再保存</template>
          <div v-for="error in validationErrors" :key="error">{{ error }}</div>
        </el-alert>

        <div class="items-head">
          <div><div class="section-title">产品明细</div><div class="section-note">{{ canReadOrderPrices ? '每种产品可设置独立的数量、价格和计划交货日期' : '查看各产品的数量和计划交货日期' }}</div></div>
          <el-button v-if="canReadOrderPrices" type="primary" plain @click="addItem">新增产品</el-button>
        </div>
        <el-table :data="items" border max-height="390">
          <el-table-column type="index" width="50" label="#" />
          <el-table-column width="170">
            <template #header><span>产品编号</span></template>
            <template #default="{ row }">
              <el-autocomplete :disabled="!canReadOrderPrices" v-model="row.productCode" :fetch-suggestions="productCodeSuggestions" value-key="value" popper-class="product-suggestion-popper" clearable style="width:100%" @input="onProductInput(row, 'productCode')" @select="item => selectProduct(row, item)">
                <template #default="{ item }"><div class="product-suggestion-item"><div class="product-suggestion-name">{{ item.value }}</div><div class="product-suggestion-sub">{{ item.sub }}</div></div></template>
              </el-autocomplete>
            </template>
          </el-table-column>
          <el-table-column width="220">
            <template #header><span class="required-column">产品名称</span></template>
            <template #default="{ row }">
              <el-autocomplete :disabled="!canReadOrderPrices" v-model="row.productName" :fetch-suggestions="productNameSuggestions" value-key="value" popper-class="product-suggestion-popper" clearable style="width:100%" @input="onProductInput(row, 'productName')" @select="item => selectProduct(row, item)">
                <template #default="{ item }"><div class="product-suggestion-item"><div class="product-suggestion-name">{{ item.value }}</div><div class="product-suggestion-sub">{{ item.sub }}</div></div></template>
              </el-autocomplete>
            </template>
          </el-table-column>
          <el-table-column width="90"><template #header><span class="required-column">单位</span></template><template #default="{ row }"><el-input :disabled="!canReadOrderPrices" v-model="row.unit" /></template></el-table-column>
          <el-table-column width="120"><template #header><span class="required-column">数量</span></template><template #default="{ row }"><el-input :disabled="!canReadOrderPrices" v-model="row.qty" @input="updateAmount(row)" /></template></el-table-column>
          <el-table-column v-if="canReadOrderPrices" width="120"><template #header><span class="required-column">单价</span></template><template #default="{ row }"><el-input :disabled="!canReadOrderPrices" v-model="row.unitPrice" @input="updateAmount(row)" /></template></el-table-column>
          <el-table-column v-if="canReadOrderPrices" label="合计金额" width="120"><template #default="{ row }"><el-input :model-value="money(row.amount, '')" disabled /></template></el-table-column>
          <el-table-column width="160">
            <template #header><span class="required-column">计划交货日期</span></template>
            <template #default="{ row }"><el-date-picker :disabled="!canReadOrderPrices" v-model="row.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></template>
          </el-table-column>
          <el-table-column v-if="canReadOrderPrices" label="操作" width="80" fixed="right">
            <template #default="{ row, $index }"><el-button link type="danger" :disabled="Number(row.shippedQty || 0) > 0" @click="removeItem($index)">删除</el-button></template>
          </el-table-column>
        </el-table>
        <div v-if="canReadOrderPrices" class="order-total">订单总金额：<strong>{{ orderTotal }}</strong></div>
        <template #footer><el-button @click="dialogVisible=false">{{ canReadOrderPrices ? '取消' : '关闭' }}</el-button><el-button v-if="canReadOrderPrices" type="primary" :loading="saving" @click="save">保存</el-button></template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import { formatMoney as money, moneyColumn, priceColumn, decimalMultiply, decimalSum } from '@/format/money'
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { createLatestRequest } from '@/api/latestRequest'
import { ElMessage } from 'element-plus'
import { getAction, postAction, remove, type PageResponse } from '@/api/crud'
import http from '@/api/http'
import { useUserStore } from '@/stores/user'
import { loadCustomers, loadProducts } from '@/api/referenceData'
import { usePageAutoRefresh } from '@/composables/usePageAutoRefresh'
import { compareProductCodeMatches, compareProductNameMatches, findExactProduct, productCodeMatches, productCodeSuggestionSub, productNameMatches, productNameSuggestionSub } from '@/product/productSearch'

const user = useUserStore()
const canReadOrderPrices = computed(() => user.canReadOrderPrices)
const rows = shallowRef<any[]>([])
const totalRows = ref(0)
const customers = ref<any[]>([])
const products = ref<any[]>([])
const items = ref<any[]>([])
const keyword = ref('')
const deliveryStatusOptions = ['未发货', '部分发货', '已送']
const deliveryStatusFilters = ref<string[]>([])
const currentYear = new Date().getFullYear()
const yearOptions = [...new Set([currentYear, 2025])].sort((a, b) => b - a)
const yearFilter = ref(String(currentYear))
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const dialogVisible = ref(false)
const form = reactive<any>({})
const refreshingOrderNo = ref(false)
const validationErrors = ref<string[]>([])
const formErrors = reactive<Record<string, string>>({})
const saving = ref(false)

const displayRows = computed(() => rows.value.flatMap((order, orderIndex) => {
  const products = Array.isArray(order.productItems) && order.productItems.length
    ? order.productItems
    : [{ productName: order.productNames, productCode: order.productCodes, unit: order.units, qty: order.qty, unitPriceText: order.unitPriceText, amount: order.amount, dueDate: order.plannedDeliveryDate }]
  return products.map((product: any, productIndex: number) => {
    const { id: itemId, unitPrice, dueDate, ...item } = product
    return {
      ...order,
      ...item,
      id: order.id,
      itemId,
      units: product.unit || '',
      unitPriceText: unitPrice == null ? (product.unitPriceText || '-') : String(unitPrice),
      plannedDeliveryDateText: dueDate || order.deliveryDate || '-',
      _groupFirst: productIndex === 0,
      _groupSize: products.length,
      _groupIndex: (currentPage.value - 1) * pageSize.value + orderIndex
    }
  })
}))
const selectedCustomerContacts = computed(() => customers.value.find(item => item.id === form.customerId)?.contacts || [])
const productsByCode = computed(() => [...products.value].sort((a, b) => String(a.code || '').localeCompare(String(b.code || ''), 'zh-Hans-CN')))
const productsByName = computed(() => [...products.value].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN')))
const orderTotal = computed(() => money(decimalSum(items.value.map(item => item.amount))))

function rowIndex(index: number) { return (displayRows.value[index]?._groupIndex ?? index) + 1 }
function orderSpanMethod({ row, column }: { row: any; column: { type: string; property: string; label: string } }) {
  if (column.type !== 'index' && column.label !== '操作' && !['orderNo', 'orderDate', 'customerName', 'contactPerson', 'deliveryStatus'].includes(column.property)) return [1, 1]
  return row._groupFirst ? [row._groupSize, 1] : [0, 0]
}
function deliveryStatusType(status?: string) { return status === '已送' ? 'success' : status === '部分发货' ? 'warning' : 'info' }
function handleDeliveryStatusChange(statuses: string[]) {
  if (statuses.includes('all')) deliveryStatusFilters.value = []
}
async function load(targetPage = currentPage.value, targetPageSize = pageSize.value) {
  const params = new URLSearchParams({
    page: String(targetPage - 1),
    size: String(targetPageSize),
    keyword: keyword.value.trim(),
    deliveryStatus: deliveryStatusFilters.value.join(',') || 'all'
  })
  if (yearFilter.value !== 'all') params.set('year', yearFilter.value)
  const result = await pageRequest.run(signal => getAction<PageResponse<any>>(`/sales/orders/page?${params}`, { signal }))
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
async function loadFirstPage() {
  await load(1, pageSize.value)
}
function changePage(page: number) { void load(page, pageSize.value) }
function changePageSize(size: number) { void load(1, size) }
async function loadSources() {
  const [customerRows, productRows] = await Promise.all([loadCustomers(), loadProducts()])
  customers.value = customerRows
  products.value = productRows
}
function resetForm() { Object.keys(form).forEach(key => delete form[key]); Object.keys(formErrors).forEach(key => delete formErrors[key]); items.value = []; validationErrors.value = [] }
function blankItem() { return { productId: null, productCode: '', productName: '', unit: '条', qty: '', unitPrice: '0', amount: '', dueDate: '' } }
async function openCreate() {
  if (!canReadOrderPrices.value) return
  resetForm()
  form.orderDate = todayText()
  form.orderNo = (await Promise.all([nextOrderNo(form.orderDate), loadSources()]))[0]
  items.value = [blankItem()]
  dialogVisible.value = true
}
async function openEdit(row: any) {
  resetForm()
  const [detail] = await Promise.all([getAction<any>(`/sales/orders/${row.id}/detail`), loadSources()])
  Object.assign(form, detail.order)
  items.value = (detail.items || []).map((item: any) => ({ ...item }))
  if (!items.value.length) items.value = [blankItem()]
  dialogVisible.value = true
}
function addItem() { items.value.push(blankItem()) }
function removeItem(index: number) {
  if (Number(items.value[index]?.shippedQty || 0) > 0) return ElMessage.warning('该产品已有发货记录，不能删除')
  items.value.splice(index, 1)
}
function enteredItems() { return items.value.filter(item => item.productId || item.productCode || item.productName || item.qty || item.unitPrice) }
function validateOrder() {
  Object.keys(formErrors).forEach(key => delete formErrors[key])
  const errors: string[] = []
  if (!String(form.orderNo || '').trim()) { formErrors.orderNo = '订单号未填写'; errors.push('订单号未填写') }
  if (!String(form.orderDate || '').trim()) { formErrors.orderDate = '订单日期未填写'; errors.push('订单日期未填写') }
  if (!form.customerId || !String(form.customerName || '').trim()) { formErrors.customerName = '客户未选择或资料中不存在'; errors.push('客户资料中没有该客户，请先在客户档案新增或从提示中选择') }
  const validItems = items.value.map((item, index) => ({ item, rowNo: index + 1 })).filter(({ item }) => item.productId || item.productCode || item.productName || item.qty)
  if (!validItems.length) errors.push('请至少填写一条产品明细')
  for (const { item, rowNo } of validItems) {
    resolveExactProduct(item)
    if (!item.productId) errors.push(`第${rowNo}条：产品资料中没有该产品，请先在产品资料新增或从提示中选择`)
    if (!String(item.unit || '').trim()) errors.push(`第${rowNo}条产品明细请填写单位`)
    if (!String(item.qty ?? '').trim() || !Number.isFinite(Number(item.qty)) || Number(item.qty) <= 0) errors.push(`第${rowNo}条产品明细数量必须大于0`)
    if (!String(item.unitPrice ?? '').trim() || !Number.isFinite(Number(item.unitPrice)) || Number(item.unitPrice) < 0) errors.push(`第${rowNo}条产品明细请填写不小于0的单价`)
    if (!String(item.dueDate || '').trim()) errors.push(`第${rowNo}条产品明细计划交货日期未填写`)
  }
  validationErrors.value = errors
  if (errors.length) ElMessage.error(errors[0])
  return errors.length === 0
}
async function save() {
  if (!canReadOrderPrices.value) return
  if (!validateOrder()) return
  saving.value = true
  try {
    const creating = !form.id
    await postAction('/sales/orders/with-items', { order: { ...form, deliveryDate: null }, items: enteredItems() })
    if (creating) await loadFirstPage()
    else await load()
    ElMessage.success('订单已保存')
    dialogVisible.value = false
  } finally {
    saving.value = false
  }
}
async function deleteOrder(row: any) { if (!canReadOrderPrices.value) return; await remove('/sales/orders', row.id); await load(); ElMessage.success('订单已删除') }
async function downloadOrder(row: any) {
  const blob = await http.get<Blob, Blob>(`/sales/orders/${row.id}/export`, { responseType: 'blob' })
  const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `${row.orderNo || '销售订单'}.xlsx`; link.click(); URL.revokeObjectURL(url)
}
async function nextOrderNo(date?: string) { try { return await getAction<string>(`/sales/orders/next-no${date ? `?date=${encodeURIComponent(date)}` : ''}`) } catch { return '' } }
function todayText() { const date = new Date(); return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}` }
function customerSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim().toLowerCase(); if (!key) return cb([])
  cb(customers.value.filter(customer => [customer.name, customer.contactNames, customer.contactPerson].join(' ').toLowerCase().includes(key)).slice(0, 8).map(customer => ({ value: customer.name, sub: customer.contactNames || customer.contactPerson || '', raw: customer })))
}
function selectCustomer(item: any) {
  const customer = item.raw || {}; const primary = (customer.contacts || []).find((contact: any) => contact.primaryContact) || customer.contacts?.[0]
  Object.assign(form, { customerId: customer.id, customerName: customer.name, customerAddress: customer.address || '', customerPhone: primary?.phone || customer.phone || '', customerEmail: primary?.email || customer.email || '', contactPerson: primary?.name || customer.contactPerson || '' })
}
function onCustomerInput(value: string) {
  const selected = customers.value.find(customer => customer.id === form.customerId)
  if (selected && String(selected.name || '') !== String(value || '')) form.customerId = null
  validationErrors.value = []
}
function selectContact(name: string) { const contact = selectedCustomerContacts.value.find((item: any) => item.name === name); if (contact) { form.customerPhone = contact.phone || ''; form.customerEmail = contact.email || '' } }
function productCodeSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim(); if (!key) return cb([])
  cb(productsByCode.value.filter(product => productCodeMatches(product, key)).sort((a, b) => compareProductCodeMatches(a, b, key)).map(product => ({ value: product.code, sub: productCodeSuggestionSub(product), raw: product })))
}
function productNameSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim(); if (!key) return cb([])
  cb(productsByName.value.filter(product => productNameMatches(product, key)).sort((a, b) => compareProductNameMatches(a, b, key)).map(product => ({ value: product.name, sub: productNameSuggestionSub(product, key), raw: product })))
}
function selectProduct(row: any, item: any) { const product = item.raw || {}; Object.assign(row, { productId: product.id, productCode: product.code || '', productName: product.name, spec: product.spec || '', unit: product.unit || '', unitPrice: product.defaultPrice ?? row.unitPrice }); updateAmount(row) }
function onProductInput(row: any, field: 'productCode' | 'productName') {
  const selected = products.value.find(product => String(product.id) === String(row.productId))
  if (selected && String(selected[field] || '') !== String(row[field] || '')) row.productId = null
  validationErrors.value = []
}
function resolveExactProduct(row: any) {
  if (row.productId) return
  const exact = findExactProduct(products.value, row.productCode, row.productName)
  if (exact) selectProduct(row, { raw: exact })
}
function updateAmount(row: any) { const qty = Number(row.qty); const price = Number(row.unitPrice); row.amount = Number.isFinite(qty) && Number.isFinite(price) ? decimalMultiply(row.qty, row.unitPrice) : '' }
function escapeHtml(value: string) { return value.replace(/[&<>"']/g, char => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[char] || char)) }
function highlight(value: string, query: string) { const safe = escapeHtml(String(value || '')); const key = String(query || '').trim(); if (!key) return safe; return safe.replace(new RegExp(key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), text => `<span class="suggestion-hit">${text}</span>`) }

const pageRequest = createLatestRequest()
let searchTimer: ReturnType<typeof window.setTimeout> | undefined
function scheduleLoad() {
  if (searchTimer) window.clearTimeout(searchTimer)
  pageRequest.cancel()
  searchTimer = window.setTimeout(() => { void load(1, pageSize.value) }, 250)
}

onMounted(load)
usePageAutoRefresh(load, dialogVisible)
watch(() => form.orderDate, async (date, oldDate) => { if (!dialogVisible.value || form.id || !date || date === oldDate || refreshingOrderNo.value) return; refreshingOrderNo.value = true; form.orderNo = await nextOrderNo(date); refreshingOrderNo.value = false })
watch([keyword, deliveryStatusFilters, yearFilter], scheduleLoad)
watch(canReadOrderPrices, () => { dialogVisible.value = false; resetForm(); void load() })
onBeforeUnmount(() => { if (searchTimer) window.clearTimeout(searchTimer); pageRequest.cancel() })
</script>

<style scoped>
.suggestion-main { line-height: 20px; }
.suggestion-sub { color: var(--oms-sub); font-size: 12px; line-height: 18px; }
:deep(.suggestion-hit) { color: var(--oms-primary); font-weight: 760; }
.order-actions { align-items: center; display: flex; flex-wrap: wrap; gap: 2px 10px; }
.order-actions :deep(.el-button) { margin-left: 0; padding: 0; }
.items-head { align-items: center; display: flex; justify-content: space-between; margin: 8px 0 10px; }
.section-title { color: var(--oms-heading); font-size: 15px; font-weight: 760; }
.section-note { color: var(--oms-sub); font-size: 12px; margin-top: 3px; }
.required-column::before { color: #f56c6c; content: '*'; margin-right: 4px; }
.order-total { color: var(--oms-sub); font-size: 14px; margin-top: 12px; text-align: right; }
.order-total strong { color: var(--oms-heading); font-size: 18px; margin-left: 8px; }
.validation-alert { margin: 4px 0 12px; }
.validation-alert div { line-height: 22px; }
.status-filter-option { align-items: center; display: flex; gap: 10px; }
.status-filter-option :deep(.el-checkbox) { pointer-events: none; }
.order-filter-grid {
  grid-template-columns: minmax(320px, 2fr) minmax(220px, 1fr) minmax(160px, 0.75fr);
  gap: 10px 24px;
}
.order-filter-grid :deep(.el-form-item__content) { min-width: 0; }
@media (max-width: 1200px) {
  .order-filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .order-filter-grid > :first-child { grid-column: 1 / -1; }
}
@media (max-width: 900px) {
  :deep(.el-dialog .el-col-6),
  :deep(.el-dialog .el-col-8),
  :deep(.el-dialog .el-col-16) {
    flex: 0 0 100%;
    max-width: 100%;
  }
  .items-head { align-items: flex-start; }
}
@media (max-width: 760px) {
  .order-filter-grid { grid-template-columns: minmax(0, 1fr); }
  .order-filter-grid > :first-child { grid-column: auto; }
}
</style>
