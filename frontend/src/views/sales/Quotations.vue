<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">报价管理</div>
      <div class="muted">录入客户与产品明细后，按模板自动生成报价单。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="报价单号 / 公司名称 / 产品编号 / 产品名称 / 联系人" clearable />
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="left-actions">
          <el-button type="primary" @click="openCreate">新增</el-button>
        </div>
        <div class="right-actions">
          <span class="table-count">共 {{ filteredRows.length }} 条</span>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table class="quotation-list-table" :data="displayRows" :span-method="quotationSpanMethod" height="calc(100vh - 270px)" border stripe>
        <el-table-column type="index" :index="rowIndex" width="50" label="#" fixed />
        <el-table-column prop="quoteDate" label="报价日期" width="150" />
        <el-table-column prop="quoteNo" label="报价单号" width="150" />
        <el-table-column prop="customerName" label="公司名称" width="150" show-overflow-tooltip />
        <el-table-column prop="productCode" label="产品编号" width="180" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" width="150" show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="150" />
        <el-table-column prop="totalAmount" label="报价金额" width="150" :formatter="moneyColumn" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="success" @click="generate(row)">生成报价单</el-button>
              <el-popconfirm title="确认删除该报价？" @confirm="deleteQuotation(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
              <el-button v-if="user.canReadOrderPrices" link type="warning" @click="openConvertDialog(row)">生成销售订单</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="PAGE_SIZE_OPTIONS" :total="filteredRows.length" layout="total, sizes, prev, pager, next, jumper" />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑报价' : '新增报价'" width="1040px">
      <el-form :model="form" label-width="120px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="报价日期" required :error="formErrors.quoteDate">
              <el-date-picker v-model="form.quoteDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="报价单号" required :error="formErrors.quoteNo"><el-input v-model="form.quoteNo" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="公司名称" required :error="formErrors.customerName">
              <el-autocomplete
                v-model="form.customerName"
                :fetch-suggestions="customerSuggestions"
                value-key="value"
                clearable
                style="width: 100%"
                @input="onCustomerInput"
                @select="selectCustomer"
              >
                <template #default="{ item }">
                  <div class="suggestion-main" v-html="highlight(item.value, form.customerName)"></div>
                  <div v-if="item.sub" class="suggestion-sub">{{ item.sub }}</div>
                </template>
              </el-autocomplete>
            </el-form-item>
          </el-col>
          <el-col :span="16"><el-form-item label="公司地址/邮编"><el-input v-model="form.customerAddress" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="邮箱"><el-input v-model="form.customerEmail" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="联系人"><el-input v-model="form.contactPerson" /></el-form-item></el-col>
        </el-row>
      </el-form>

      <div class="items-head">
        <div class="section-title">产品明细</div>
        <el-button type="primary" plain @click="addItem">新增产品</el-button>
      </div>
      <el-table :data="items" border>
        <el-table-column type="index" width="50" label="#" />
        <el-table-column width="150">
          <template #header><span>产品编号</span></template>
          <template #default="{ row }">
            <el-autocomplete
              v-model="row.productCode"
              :fetch-suggestions="productCodeSuggestions"
              value-key="value"
              popper-class="product-suggestion-popper"
              clearable
              style="width: 100%"
              @input="onProductInput(row, 'productCode')"
              @select="item => selectProduct(row, item)"
            >
              <template #default="{ item }">
                <div class="product-suggestion-item">
                  <div class="product-suggestion-name">{{ item.value }}</div>
                  <div class="product-suggestion-sub">{{ item.sub }}</div>
                </div>
              </template>
            </el-autocomplete>
          </template>
        </el-table-column>
        <el-table-column width="150">
          <template #header><span class="required-column">产品名称</span></template>
          <template #default="{ row }">
            <el-autocomplete
              v-model="row.productName"
              :fetch-suggestions="productSuggestions"
              value-key="value"
              popper-class="product-suggestion-popper"
              clearable
              style="width: 100%"
              @input="onProductInput(row, 'productName')"
              @select="item => selectProduct(row, item)"
            >
              <template #default="{ item }">
                <div class="product-suggestion-item">
                  <div class="product-suggestion-name">{{ item.value }}</div>
                  <div class="product-suggestion-sub">{{ item.sub }}</div>
                </div>
              </template>
            </el-autocomplete>
          </template>
        </el-table-column>
        <el-table-column width="150">
          <template #header><span class="required-column">计量单位</span></template>
          <template #default="{ row }"><el-input v-model="row.unit" /></template>
        </el-table-column>
        <el-table-column width="150">
          <template #header><span class="required-column">数量</span></template>
          <template #default="{ row }"><el-input v-model="row.qty" @input="updateAmount(row)" /></template>
        </el-table-column>
        <el-table-column width="150">
          <template #header><span class="required-column">单价</span></template>
          <template #default="{ row }"><el-input v-model="row.unitPrice" @input="updateAmount(row)" /></template>
        </el-table-column>
        <el-table-column label="合计金额" width="150">
          <template #default="{ row }"><el-input v-model="row.amount" /></template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ $index }"><el-button link type="danger" @click="items.splice($index, 1)">删除</el-button></template>
        </el-table-column>
      </el-table>

      <el-progress v-if="generating" :percentage="progress" style="margin-top: 16px" />

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button @click="save">保存</el-button>
        <el-button type="primary" :loading="generating" @click="saveAndGenerate">自动生成报价单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="convertDialogVisible" title="生成销售订单" width="620px">
      <el-form :model="convertForm" label-width="100px">
        <el-form-item label="订单日期" required>
          <el-date-picker v-model="convertForm.orderDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="审核人"><el-input v-model="convertForm.auditor" /></el-form-item>
        <el-form-item label="订单号" required><el-input v-model="convertForm.orderNo" /></el-form-item>
        <el-form-item label="交货日期">
          <el-date-picker v-model="convertForm.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="convertDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitConvert">确认转单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import { moneyColumn, decimalMultiply } from '@/format/money'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getAction, list, postAction, remove } from '@/api/crud'
import http from '@/api/http'
import { useUserStore } from '@/stores/user'
import { loadCustomers, loadProducts } from '@/api/referenceData'
import { usePageAutoRefresh } from '@/composables/usePageAutoRefresh'
import { compareProductCodeMatches, compareProductNameMatches, findExactProduct, productCodeMatches, productCodeSuggestionSub, productNameMatches, productNameSuggestionSub } from '@/product/productSearch'

const user = useUserStore()
const rows = ref<any[]>([])
const customers = ref<any[]>([])
const products = ref<any[]>([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const dialogVisible = ref(false)
const convertDialogVisible = ref(false)
const generating = ref(false)
const progress = ref(0)
const form = reactive<any>({})
const formErrors = reactive<Record<string, string>>({})
const convertForm = reactive<any>({})
const convertingQuotation = ref<any>(null)
const items = ref<any[]>([])
const refreshingQuoteNo = ref(false)
const refreshingConvertOrderNo = ref(false)

const filteredRows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter(row => JSON.stringify(row).toLowerCase().includes(k))
})
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})
const displayRows = computed(() => pagedRows.value.flatMap((quotation, quotationIndex) => {
  const products = Array.isArray(quotation.productItems) && quotation.productItems.length
    ? quotation.productItems
    : [{ productName: quotation.productNames, productCode: quotation.productCodes }]
  return products.map((product: any, productIndex: number) => ({
    ...quotation,
    ...product,
    id: quotation.id,
    itemId: product.id,
    _groupFirst: productIndex === 0,
    _groupSize: products.length,
    _groupIndex: (currentPage.value - 1) * pageSize.value + quotationIndex
  }))
}))

function rowIndex(index: number) {
  return (displayRows.value[index]?._groupIndex ?? index) + 1
}

function quotationSpanMethod({ row, columnIndex }: { row: any; columnIndex: number }) {
  if (![0, 1, 2, 3, 6, 7, 8].includes(columnIndex)) return [1, 1]
  return row._groupFirst ? [row._groupSize, 1] : [0, 0]
}

async function load() {
  rows.value = await list('/sales/quotations')
  const lastPage = Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value))
  currentPage.value = Math.min(currentPage.value, lastPage)
}

async function loadSources() {
  const [customerRows, productRows] = await Promise.all([
    loadCustomers(),
    loadProducts()
  ])
  customers.value = customerRows
  products.value = productRows
}

async function openCreate() {
  resetForm()
  form.quoteDate = todayText()
  form.quoteNo = (await Promise.all([nextQuotationNo(form.quoteDate), loadSources()]))[0]
  items.value = [blankItem()]
  dialogVisible.value = true
}

async function openEdit(row: any) {
  resetForm()
  const [detail] = await Promise.all([getAction<any>(`/sales/quotations/${row.id}/detail`), loadSources()])
  Object.assign(form, detail.quotation)
  items.value = (detail.items || []).map((item: any) => ({ ...item }))
  if (!items.value.length) items.value = [blankItem()]
  dialogVisible.value = true
}

function resetForm() {
  Object.keys(form).forEach(key => delete form[key])
  Object.keys(formErrors).forEach(key => delete formErrors[key])
  progress.value = 0
  generating.value = false
}

function blankItem() {
  return { productCode: '', productName: '', unit: '', qty: '', unitPrice: '', amount: '' }
}

function addItem() {
  items.value.push(blankItem())
}

async function save() {
  if (!validateQuotation()) return null
  const saved = await persist()
  Object.assign(form, saved)
  upsertQuotationRow(saved)
  ElMessage.success('报价已保存')
  dialogVisible.value = false
  return saved
}

async function persist() {
  const payload = {
    quotation: { ...form },
    items: enteredItems()
  }
  return postAction<any>('/sales/quotations/with-items', payload)
}

async function saveAndGenerate() {
  if (!validateQuotation()) return
  try {
    const saved = await persist()
    Object.assign(form, saved)
    upsertQuotationRow(saved)
    generating.value = true
    progress.value = 20
    progress.value = 65
    await downloadQuotation(saved)
    progress.value = 100
    ElMessage.success('报价单已生成')
    dialogVisible.value = false
  } finally {
    generating.value = false
  }
}

async function generate(row: any) {
  generating.value = true
  progress.value = 50
  await downloadQuotation(row)
  progress.value = 100
  generating.value = false
}

async function deleteQuotation(row: any) {
  await remove('/sales/quotations', row.id)
  rows.value = rows.value.filter(item => item.id !== row.id)
  ElMessage.success('报价已删除')
}

async function openConvertDialog(row: any) {
  if (!user.canReadOrderPrices) return
  convertingQuotation.value = row
  Object.keys(convertForm).forEach(key => delete convertForm[key])
  convertForm.orderDate = todayText()
  convertForm.orderNo = await nextOrderNo(convertForm.orderDate)
  convertForm.deliveryDate = ''
  convertDialogVisible.value = true
}

async function submitConvert() {
  if (!user.canReadOrderPrices) return
  if (!convertingQuotation.value) return
  if (!String(convertForm.orderDate || '').trim()) return ElMessage.error('订单日期未填写')
  if (!String(convertForm.orderNo || '').trim()) return ElMessage.error('订单号未填写')
  const quotation = convertingQuotation.value
  const order = await postAction<any>(`/sales/quotations/${quotation.id}/convert`, convertForm)
  const index = rows.value.findIndex(row => row.id === quotation.id)
  if (index >= 0) rows.value.splice(index, 1, { ...rows.value[index], status: '已转订单', convertedOrderNo: order.orderNo })
  ElMessage.success('已转为销售订单')
  convertDialogVisible.value = false
  convertingQuotation.value = null
}

function upsertQuotationRow(saved: any) {
  const entered = enteredItems()
  const row = {
    ...saved,
    productItems: entered.map((item: any) => ({ ...item })),
    productCodes: [...new Set(entered.map((item: any) => item.productCode).filter(Boolean))].join('、'),
    productNames: [...new Set(entered.map((item: any) => item.productName).filter(Boolean))].join('、')
  }
  const index = rows.value.findIndex(item => item.id === saved.id)
  if (index >= 0) rows.value.splice(index, 1, row)
  else rows.value.unshift(row)
}

async function downloadQuotation(row: any) {
  const blob = await http.get<Blob, Blob>(`/sales/quotations/${row.id}/export`, { responseType: 'blob' })
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank')
}

async function nextQuotationNo(date?: string) {
  try {
    return await getAction<string>(`/sales/quotations/next-no${date ? `?date=${encodeURIComponent(date)}` : ''}`)
  } catch {
    return ''
  }
}

async function nextOrderNo(date?: string) {
  try {
    return await getAction<string>(`/sales/orders/next-no${date ? `?date=${encodeURIComponent(date)}` : ''}`)
  } catch {
    return ''
  }
}

function todayText() {
  const date = new Date()
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function customerSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim().toLowerCase()
  if (!key) return cb([])
  cb(customers.value
    .filter(customer => String(customer.name || '').toLowerCase().includes(key))
    .slice(0, 8)
    .map(customer => ({
      value: customer.name,
      sub: customerSubText(customer),
      raw: customer
    })))
}

function customerSubText(customer: any) {
  const parts = [
    customer.contactPerson ? `联系人：${customer.contactPerson}` : '',
    customer.email ? `邮箱：${customer.email}` : ''
  ].filter(Boolean)
  return parts.join('  ')
}

function selectCustomer(item: any) {
  const customer = item.raw || {}
  form.customerId = customer.id
  form.customerName = customer.name
  form.customerAddress = customer.address || ''
  form.customerEmail = customer.email || ''
  form.contactPerson = customer.contactPerson || ''
}

function onCustomerInput(value: string) {
  const selected = customers.value.find(customer => customer.id === form.customerId)
  if (selected && String(selected.name || '') !== String(value || '')) form.customerId = null
}

function productSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim()
  if (!key) return cb([])
  cb(productsByName.value
    .filter(product => productNameMatches(product, key))
    .sort((a, b) => compareProductNameMatches(a, b, key))
    .map(product => ({
      value: product.name,
      label: product.name,
      sub: productNameSuggestionSub(product, key),
      raw: product
    })))
}

function productCodeSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim()
  if (!key) return cb([])
  cb(productsByCode.value
    .filter(product => productCodeMatches(product, key))
    .sort((a, b) => compareProductCodeMatches(a, b, key))
    .map(product => ({
      value: product.code || product.name,
      label: product.code || product.name,
      sub: productCodeSuggestionSub(product),
      raw: product
    })))
}

const productsByName = computed(() => [...products.value].sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN')))
const productsByCode = computed(() => [...products.value].sort((a, b) => String(a.code || '').localeCompare(String(b.code || ''), 'zh-Hans-CN')))

function selectProduct(row: any, item: any) {
  const product = item.raw || {}
  row.productId = product.id
  row.productCode = product.code
  row.productName = product.name
  row.unit = product.unit || ''
  row.unitPrice = product.defaultPrice ?? row.unitPrice
  updateAmount(row)
}

function onProductInput(row: any, field: 'productCode' | 'productName') {
  const selected = products.value.find(product => String(product.id) === String(row.productId))
  if (selected && String(selected[field] || '') !== String(row[field] || '')) row.productId = null
}

function updateAmount(row: any) {
  const qty = Number(row.qty)
  const price = Number(row.unitPrice)
  row.amount = Number.isFinite(qty) && Number.isFinite(price) ? decimalMultiply(row.qty, row.unitPrice) : ''
}

function validateQuotation() {
  Object.keys(formErrors).forEach(key => delete formErrors[key])
  if (!String(form.quoteNo || '').trim()) {
    formErrors.quoteNo = '报价单号未填写'
    ElMessage.error('报价单号未填写')
    return false
  }
  if (!String(form.quoteDate || '').trim()) {
    formErrors.quoteDate = '报价日期未填写'
    ElMessage.error('报价日期未填写')
    return false
  }
  if (!form.customerId || !String(form.customerName || '').trim()) {
    formErrors.customerName = '客户未选择或资料中不存在'
    ElMessage.error('客户资料中没有该客户，请先在客户档案新增或从提示中选择')
    return false
  }
  const validItems = enteredItems()
  if (!validItems.length) {
    ElMessage.error('请至少填写一条产品明细')
    return false
  }
  for (let index = 0; index < validItems.length; index++) {
    const item = validItems[index]
    const rowNo = index + 1
    resolveExactProduct(item)
    if (!item.productId) {
      ElMessage.error(`第${rowNo}条：产品资料中没有该产品，请先在产品资料新增或从提示中选择`)
      return false
    }
    if (!String(item.productName || '').trim()) {
      ElMessage.error(`第${rowNo}条产品明细请填写产品名称`)
      return false
    }
    if (!String(item.unit || '').trim()) {
      ElMessage.error(`第${rowNo}条产品明细请填写计量单位`)
      return false
    }
    const qty = Number(item.qty)
    if (!Number.isFinite(qty) || qty <= 0) {
      ElMessage.error(`第${rowNo}条产品明细数量必须大于0`)
      return false
    }
    const price = Number(item.unitPrice)
    if (!Number.isFinite(price) || price < 0) {
      ElMessage.error(`第${rowNo}条产品明细单价不能小于0`)
      return false
    }
    updateAmount(item)
  }
  return true
}

function enteredItems() {
  return items.value.filter(item => item.productCode || item.productName || item.qty || item.unitPrice)
}

function resolveExactProduct(row: any) {
  if (row.productId) return
  const exact = findExactProduct(products.value, row.productCode, row.productName)
  if (exact) selectProduct(row, { raw: exact })
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, char => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[char] || char))
}

function highlight(value: string, query: string) {
  const safeValue = escapeHtml(String(value || ''))
  const key = String(query || '').trim()
  if (!key) return safeValue
  const escapedKey = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safeValue.replace(new RegExp(escapedKey, 'gi'), matched => `<span class="suggestion-hit">${matched}</span>`)
}

onMounted(load)
usePageAutoRefresh(load, () => dialogVisible.value || convertDialogVisible.value)

watch(() => form.quoteDate, async (date, oldDate) => {
  if (!dialogVisible.value || form.id || !date || date === oldDate || refreshingQuoteNo.value) return
  refreshingQuoteNo.value = true
  form.quoteNo = await nextQuotationNo(date)
  refreshingQuoteNo.value = false
})

watch(() => convertForm.orderDate, async (date, oldDate) => {
  if (!convertDialogVisible.value || !date || date === oldDate || refreshingConvertOrderNo.value) return
  refreshingConvertOrderNo.value = true
  convertForm.orderNo = await nextOrderNo(date)
  refreshingConvertOrderNo.value = false
})
watch(keyword, () => { currentPage.value = 1 })
watch(pageSize, () => { currentPage.value = 1 })
</script>

<style scoped>
.items-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.row-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: center;
  justify-items: start;
  column-gap: 8px;
  row-gap: 2px;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
  padding: 0;
}

.items-head {
  justify-content: space-between;
  margin: 10px 0 12px;
}

.section-title {
  font-size: 16px;
  font-weight: 760;
}

.required-column::before {
  color: var(--el-color-danger);
  content: '*';
  margin-right: 4px;
}

.suggestion-main {
  line-height: 20px;
}

.suggestion-sub {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
}

:deep(.suggestion-hit) {
  color: var(--oms-primary);
  font-weight: 760;
}

</style>
