<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">产品资料</div>
      <div class="muted">维护产品编码、规格和产品大类。</div>
    </div>

    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide">
          <div class="filter-label">快速筛选</div>
          <el-input v-model="keyword" placeholder="产品编码 / 产品名称 / 别称 / 规格" clearable />
          <div v-if="isUniversalCodePrefix" class="filter-hint">所有产品编码都以 R 开头，请继续输入 RA / RB / RC 或产品名称、规格。</div>
        </div>
        <div class="filter-field">
          <div class="filter-label">产品大类</div>
          <el-select v-model="categoryFilter" placeholder="全部大类" clearable filterable>
            <el-option v-for="item in productCategories" :key="item.code" :label="`${item.code} ${item.name}`" :value="item.code" />
          </el-select>
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
      <el-table :data="pagedRows" height="calc(100vh - 270px)" border stripe>
        <el-table-column type="index" :index="rowIndex" width="50" label="#" fixed />
        <el-table-column prop="code" label="产品编码" width="190" fixed show-overflow-tooltip />
        <el-table-column prop="name" label="产品名称" width="230" show-overflow-tooltip />
        <el-table-column prop="aliasesText" label="别称" width="220" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="deleteProduct(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="PAGE_SIZE_OPTIONS" :total="filteredRows.length" layout="total, sizes, prev, pager, next, jumper" />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑产品' : '新增产品'" width="980px" destroy-on-close>
      <el-form :model="form" label-width="132px">
        <div class="section-title">编码信息</div>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="产品大类" required :error="fieldErrors.categoryCode">
              <el-select
                v-model="form.categoryCode"
                filterable
                allow-create
                default-first-option
                placeholder="可选择或直接输入"
                style="width: 100%"
                @change="changeCategory"
              >
                <el-option v-for="item in productCategories" :key="item.code" :label="`${item.code} ${item.name}`" :value="item.code" />
              </el-select>
            </el-form-item>
          </el-col>

          <el-col :span="8">
            <el-form-item label="暂不编号">
              <el-switch v-model="unnumbered" @change="changeNumberingMode" />
            </el-form-item>
          </el-col>

          <template v-if="!unnumbered">
            <el-col v-for="field in visibleTagFields" :key="field.key" :span="8">
              <el-form-item :label="field.label" :required="isRequired(field)">
                <el-select
                  v-if="field.type === 'select'"
                  v-model="form.tagValues[field.key]"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  placeholder="可选择或直接输入"
                  style="width: 100%"
                  @change="syncGenerated"
                >
                  <el-option v-for="option in field.options || []" :key="option.value" :label="`${option.value} ${option.label}`" :value="option.value" />
                </el-select>
                <el-autocomplete
                  v-else-if="field.type === 'input'"
                  v-model="form.tagValues[field.key]"
                  :fetch-suggestions="(query, cb) => tagSuggestions(field, query, cb)"
                  clearable
                  style="width: 100%"
                  @input="syncGenerated"
                  @select="syncGenerated"
                />
                <el-switch
                  v-else-if="field.type === 'switch'"
                  v-model="form.tagValues[field.key]"
                  active-text="是"
                  inactive-text="否"
                  inline-prompt
                  @change="syncGenerated"
                />
                <el-input v-else :model-value="String(field.fixedValue ?? '')" disabled />
              </el-form-item>
            </el-col>
          </template>

          <el-col :span="8">
            <el-form-item label="产品编码">
              <el-input :model-value="unnumbered ? '' : generated.productCode" readonly :placeholder="unnumbered ? '暂未编号' : ''" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="产品名称" required :error="fieldErrors.name">
              <div class="inline-field">
                <el-input v-model="form.name" @input="manualName = true" />
                <el-button @click="restoreName">恢复</el-button>
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="规格">
              <div class="inline-field">
                <el-input v-model="form.spec" @input="manualSpec = true" />
                <el-button @click="restoreSpec">恢复</el-button>
              </div>
            </el-form-item>
          </el-col>
        </el-row>

        <div class="section-title">产品别称</div>
        <div class="alias-editor">
          <div v-for="(_, index) in form.aliases" :key="index" class="alias-row">
            <el-input v-model="form.aliases[index]" placeholder="输入客户常用叫法或历史名称" maxlength="200" />
            <el-button :icon="Delete" circle plain type="danger" title="删除别称" @click="removeAlias(index)" />
          </div>
          <el-button :icon="Plus" plain @click="addAlias">新增别称</el-button>
        </div>

        <div class="section-title">备注</div>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" />
        </el-form-item>

        <div class="preview-box">
          <div><span>产品编码</span><strong>{{ unnumbered ? '暂未编号' : generated.productCode }}</strong></div>
          <div><span>产品名称</span><strong>{{ generated.productName || '-' }}</strong></div>
          <div><span>规格</span><strong>{{ generated.spec || '-' }}</strong></div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { create, getAction, list, remove, update } from '@/api/crud'
import {
  categoryByCode,
  productCategories,
  type Product,
  type ProductTagField,
  type ProductTagValues
} from '@/product/productCategoryConfig'
import { generateProductIdentity, isProductCodeDuplicated, withFixedValues } from '@/product/productCodeGenerator'
import { productAliases, productAliasText } from '@/product/productSearch'

const rows = ref<Product[]>([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const categoryFilter = ref('')
const dialogVisible = ref(false)
const manualName = ref(false)
const manualSpec = ref(false)
const unnumbered = ref(false)

const form = reactive<Product & { tagValues: ProductTagValues }>({
  code: '',
  name: '',
  categoryCode: '',
  categoryName: '',
  spec: '',
  unit: '条',
  defaultPrice: 0,
  costPrice: 0,
  safetyStock: 0,
  enabled: true,
  status: 'enabled',
  remark: '',
  aliases: [],
  tagValues: {}
})
const fieldErrors = reactive<Record<string, string>>({})

const generated = computed(() => generateProductIdentity(form.categoryCode, form.tagValues))
const currentCategory = computed(() => categoryByCode(form.categoryCode))
const visibleTagFields = computed(() => {
  const values = withFixedValues(form.categoryCode, form.tagValues)
  return (currentCategory.value?.fields || []).filter(field => field.showWhen ? field.showWhen(values) : true)
})
const isUniversalCodePrefix = computed(() => keyword.value.trim().toUpperCase() === 'R')

const filteredRows = computed(() => {
  const rawKey = keyword.value.trim()
  const key = rawKey.toLowerCase()
  return rows.value.filter(row => {
    const matchKeyword = !key || matchesKeyword(row, rawKey)
    const matchCategory = !categoryFilter.value || row.categoryCode === categoryFilter.value
    return matchKeyword && matchCategory
  }).sort((a, b) => {
    const aCode = String(a.code || '').trim()
    const bCode = String(b.code || '').trim()
    if (!aCode && bCode) return -1
    if (aCode && !bCode) return 1
    if (!aCode && !bCode) return String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN')
    return aCode.localeCompare(bCode, 'zh-Hans-CN', { numeric: true })
  })
})
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

function rowIndex(index: number) {
  return (currentPage.value - 1) * pageSize.value + index + 1
}

function matchesKeyword(row: Product, rawKey: string) {
  const key = rawKey.trim().toLowerCase()
  const upperKey = rawKey.trim().toUpperCase()
  if (!key) return true
  if (upperKey === 'R') {
    return [row.name, row.spec, row.categoryName, ...productAliases(row)].some(value => String(value || '').toLowerCase().includes(key))
  }
  if (/^[A-Z]$/.test(upperKey)) {
    return String(row.categoryCode || '').toUpperCase() === upperKey
      || [row.name, row.spec, row.categoryName, ...productAliases(row)].some(value => String(value || '').toLowerCase().includes(key))
  }
  return [row.code, row.name, row.spec, row.categoryName, ...productAliases(row)].some(value => String(value || '').toLowerCase().includes(key))
}

async function load() {
  rows.value = (await list<Product>('/master/products')).map(normalizeProductRow)
  const lastPage = Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value))
  currentPage.value = Math.min(currentPage.value, lastPage)
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: Product) {
  resetForm(row)
  dialogVisible.value = true
}

interface ProductReferenceInfo {
  referenced: boolean
  usedInOrders: boolean
  usedInQuotations: boolean
  usedInInventory: boolean
  usedInProduction: boolean
  usedInQuality: boolean
}

async function deleteProduct(row: Product) {
  if (!row.id) return
  const references = await getAction<ProductReferenceInfo>(`/master/products/${row.id}/references`)
  let message = `确认删除产品“${row.name}”吗？`
  if (references.usedInOrders) {
    message = `产品“${row.name}”已经用于销售订单。继续删除只会移除产品档案，已有订单、发货和财务历史会完整保留。是否继续？`
  } else if (references.referenced) {
    message = `产品“${row.name}”已经被业务记录使用。继续删除只会移除产品档案，已有历史记录会完整保留。是否继续？`
  }
  try {
    await ElMessageBox.confirm(message, '删除产品', {
      confirmButtonText: '继续删除',
      cancelButtonText: '取消',
      type: references.referenced ? 'warning' : 'info'
    })
  } catch {
    return
  }
  await remove('/master/products', references.referenced ? `${row.id}/force` : row.id)
  rows.value = rows.value.filter(item => String(item.id) !== String(row.id))
  ElMessage.success('产品已删除')
}

function resetForm(row?: Product) {
  Object.keys(form).forEach(key => delete (form as any)[key])
  Object.keys(fieldErrors).forEach(key => delete fieldErrors[key])
  const tagValues = parseTagValues(row?.tagValues)
  Object.assign(form, {
    id: row?.id,
    code: row?.code || '',
    name: row?.name || '',
    categoryCode: row?.categoryCode || '',
    categoryName: row?.categoryName || '',
    spec: row?.spec || '',
    unit: row?.unit || '条',
    defaultPrice: row?.defaultPrice ?? 0,
    costPrice: row?.costPrice ?? 0,
    safetyStock: row?.safetyStock ?? 0,
    enabled: row?.enabled ?? true,
    status: rowStatus(row),
    remark: row?.remark || '',
    aliases: [...productAliases(row)],
    tagValues: withFixedValues(row?.categoryCode || '', tagValues)
  })
  manualName.value = !!row?.id
  manualSpec.value = !!row?.id
  unnumbered.value = !!row?.id && !String(row?.code || '').trim()
  if (!row?.id) syncGenerated()
}

function changeCategory() {
  const category = categoryByCode(form.categoryCode)
  form.categoryName = category?.name || form.categoryCode
  form.tagValues = withFixedValues(form.categoryCode, {})
  if (!category) unnumbered.value = true
  manualName.value = false
  manualSpec.value = false
  syncGenerated()
}

function syncGenerated() {
  form.tagValues = withFixedValues(form.categoryCode, form.tagValues)
  form.code = unnumbered.value ? '' : (generated.value.ready ? generated.value.productCode : '')
  if (!manualName.value) form.name = generated.value.productName
  if (!manualSpec.value) form.spec = generated.value.spec
}

function changeNumberingMode() {
  if (!unnumbered.value) {
    manualName.value = false
    manualSpec.value = false
  }
  syncGenerated()
}

function restoreName() {
  manualName.value = false
  form.name = generated.value.productName
}

function restoreSpec() {
  manualSpec.value = false
  form.spec = generated.value.spec
}

function addAlias() {
  if (!Array.isArray(form.aliases)) form.aliases = []
  form.aliases.push('')
}

function removeAlias(index: number) {
  form.aliases.splice(index, 1)
}

async function save() {
  syncGenerated()
  if (!validate()) return
  const payload = buildPayload()
  let saved: Product
  if (form.id) {
    saved = await update<Product>('/master/products', form.id, payload)
    const index = rows.value.findIndex(row => row.id === form.id)
    if (index >= 0) rows.value.splice(index, 1, normalizeProductRow(saved))
  } else {
    saved = await create<Product>('/master/products', payload)
    rows.value.unshift(normalizeProductRow(saved))
  }
  ElMessage.success('产品已保存')
  dialogVisible.value = false
}

function validate() {
  Object.keys(fieldErrors).forEach(key => delete fieldErrors[key])
  if (!form.categoryCode) return fail('categoryCode', '产品大类未填写', '请选择产品大类')
  if (!unnumbered.value && !generated.value.ready) return warn(generated.value.missingFields.length ? `请完善：${generated.value.missingFields.join('、')}` : '请完善必填字段')
  if (!String(form.name || '').trim()) return fail('name', '产品名称未填写', '产品名称不能为空')
  if (form.categoryCode === 'C' && form.tagValues.is_print === true && !String(form.tagValues.print_label || '').trim()) return warn('印花款请填写印花图案 / 客户款')
  if (form.categoryCode === 'Q' && !String(form.tagValues.model || '').trim()) return warn('EVA 请填写具体型号')
  if (!unnumbered.value && isProductCodeDuplicated(generated.value.productCode, rows.value, form.id)) return warn('该产品编码已存在，请检查规格组合。')
  return true
}

function buildPayload() {
  const category = categoryByCode(form.categoryCode)
  const tagValues = trimTagValues(withFixedValues(form.categoryCode, form.tagValues))
  return {
    ...form,
    code: unnumbered.value ? null : generated.value.productCode.trim(),
    name: String(form.name || '').trim(),
    categoryCode: form.categoryCode,
    categoryName: category?.name || form.categoryName || '',
    spec: String(form.spec || '').trim(),
    unit: String(form.unit || '').trim(),
    defaultPrice: Number(form.defaultPrice || 0),
    costPrice: Number(form.costPrice || 0),
    safetyStock: Number(form.safetyStock || 0),
    enabled: form.status === 'enabled',
    status: form.status || 'enabled',
    remark: String(form.remark || '').trim(),
    aliases: normalizeAliases(form.aliases),
    tagValues: JSON.stringify(tagValues)
  }
}

function tagSuggestions(field: ProductTagField, query: string, cb: (items: Array<{ value: string }>) => void) {
  const source = field.suggestions || []
  const key = query.trim().toLowerCase()
  cb(source
    .filter(item => !key || item.toLowerCase().includes(key))
    .slice(0, 12)
    .map(value => ({ value })))
}

function isRequired(field: ProductTagField) {
  return field.required || (form.categoryCode === 'C' && field.key === 'print_label' && form.tagValues.is_print === true)
}

function rowStatus(row?: Partial<Product>) {
  if (!row) return 'enabled'
  return row.status || (row.enabled === false ? 'disabled' : 'enabled')
}

function normalizeProductRow(row: Product): Product {
  return {
    ...row,
    aliases: productAliases(row),
    aliasesText: productAliasText(row),
    status: rowStatus(row),
    unit: row.unit || '条',
    defaultPrice: row.defaultPrice ?? 0,
    costPrice: row.costPrice ?? 0,
    safetyStock: row.safetyStock ?? 0
  }
}

function normalizeAliases(values: string[] | undefined) {
  const unique = new Map<string, string>()
  for (const value of values || []) {
    const alias = String(value || '').trim()
    if (alias) unique.set(alias.toLowerCase(), alias)
  }
  return [...unique.values()]
}

function parseTagValues(value: Product['tagValues']): ProductTagValues {
  if (!value) return {}
  if (typeof value !== 'string') return { ...value }
  try {
    return JSON.parse(value || '{}')
  } catch {
    return {}
  }
}

function trimTagValues(values: ProductTagValues) {
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [
    key,
    typeof value === 'string' ? value.trim() : value
  ]))
}

function warn(message: string) {
  ElMessage.error(message)
  return false
}

function fail(field: string, message: string, toast: string) {
  fieldErrors[field] = message
  return warn(toast)
}

watch([keyword, categoryFilter], () => { currentPage.value = 1 })
watch(pageSize, () => { currentPage.value = 1 })
onMounted(load)
</script>

<style scoped>
.section-title {
  border-left: 3px solid var(--oms-primary);
  font-size: 15px;
  font-weight: 760;
  margin: 8px 0 14px;
  padding-left: 8px;
}

.inline-field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  width: 100%;
}

.preview-box {
  background: #f8fafc;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  display: grid;
  gap: 10px;
  grid-template-columns: 1.2fr 1fr 1fr;
  margin-top: 12px;
  padding: 12px;
}

.preview-box div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.preview-box span {
  color: var(--oms-sub);
  font-size: 12px;
}

.preview-box strong {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filter-hint {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
  margin-top: 6px;
}

.alias-editor {
  display: grid;
  gap: 10px;
  margin: 0 0 20px 132px;
  width: min(620px, calc(100% - 132px));
}

.alias-row {
  display: grid;
  gap: 8px;
  grid-template-columns: minmax(0, 1fr) 32px;
}

.alias-editor > .el-button {
  justify-self: start;
}

@media (max-width: 760px) {
  .alias-editor {
    margin-left: 0;
    width: 100%;
  }
}
</style>
