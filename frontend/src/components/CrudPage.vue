<template>
  <div class="page">
    <div class="page-heading">
      <div class="page-title">{{ title }}</div>
      <div v-if="description" class="muted">{{ description }}</div>
    </div>

    <div class="filter-card">
      <div class="filter-grid">
        <el-form-item label="快速筛选">
          <el-input v-model="keyword" placeholder="请输入关键词" clearable />
        </el-form-item>
      </div>
    </div>

    <div class="table-card">
      <div class="table-actions">
        <div class="left-actions">
          <slot name="toolbar" :reload="load" />
          <el-button v-if="canCreate" type="primary" @click="openCreate">新增</el-button>
        </div>
        <div class="right-actions">
          <el-button @click="load">刷新</el-button>
        </div>
      </div>

      <el-table :data="pagedRows" height="calc(100vh - 270px)" border stripe>
        <el-table-column type="index" :index="rowIndex" width="50" label="#" fixed />
        <el-table-column
          v-for="col in visibleColumns"
          :key="col.prop"
          :prop="col.prop"
          :label="col.label"
          :width="columnWidth(col)"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <StatusTag v-if="col.type === 'status'" :status="row[col.prop]" />
            <MoneyText v-else-if="col.type === 'money'" :value="row[col.prop]" :field="col.prop" />
            <span v-else>{{ formatCellValue(col, row) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!readonly || $slots.rowActions" label="操作" :width="actionWidth" fixed="right">
          <template #default="{ row }">
            <slot name="rowActions" :row="row" :reload="load" />
            <el-button v-if="!readonly" link type="primary" @click="openEdit(row)">{{ editLabel || '编辑' }}</el-button>
            <el-popconfirm v-if="!readonly" title="确认删除该记录？" @confirm="removeRow(row)">
              <template #reference><el-button link type="danger">删除</el-button></template>
            </el-popconfirm>
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

      <el-dialog v-model="dialogVisible" :title="editing?.id ? (editLabel || '编辑') : '新增'" width="720px">
        <slot name="dialogExtra" :form="form" :editing="editing" />
        <el-form :model="form" :label-width="labelWidth">
          <el-row :gutter="12">
            <el-col v-for="field in visibleFields" :key="field.prop" :span="field.span || 12">
              <el-form-item :label="field.label" :required="field.required" :error="fieldErrors[field.prop]">
                <el-select v-if="field.type === 'select'" v-model="form[field.prop]" clearable filterable style="width: 100%" @change="clearFieldError(field.prop)">
                  <el-option v-for="op in field.options || []" :key="op.value" :label="op.label" :value="op.value" />
                </el-select>
                <el-date-picker v-else-if="field.type === 'date'" v-model="form[field.prop]" type="date" value-format="YYYY-MM-DD" style="width: 100%" @change="clearFieldError(field.prop)" />
                <el-input-number v-else-if="field.type === 'number' || field.type === 'money'" v-model="form[field.prop]" :precision="field.type === 'money' ? undefined : field.precision ?? 2" style="width: 100%" @change="clearFieldError(field.prop)" />
                <div v-else-if="field.type === 'monthDay'" class="month-day-input">
                  <el-input-number v-model="monthDayValues[field.prop].month" :min="1" :max="12" :precision="0" controls-position="right" @change="clearFieldError(field.prop)" />
                  <span>月</span>
                  <el-input-number v-model="monthDayValues[field.prop].day" :min="1" :max="31" :precision="0" controls-position="right" @change="clearFieldError(field.prop)" />
                  <span>日</span>
                </div>
                <el-autocomplete
                  v-else-if="field.type === 'autocomplete'"
                  v-model="form[field.prop]"
                  :fetch-suggestions="(query, cb) => fetchSuggestions(field, query, cb)"
                  :trigger-on-focus="false"
                  value-key="value"
                  clearable
                  style="width: 100%"
                  @select="(item) => selectSuggestion(field, item)"
                  @input="clearFieldError(field.prop)"
                >
                  <template #default="{ item }">
                    <div class="suggestion-main" v-html="highlightSuggestion(item.value, form[field.prop])"></div>
                    <div v-if="item.sub" class="suggestion-sub">{{ item.sub }}</div>
                  </template>
                </el-autocomplete>
                <el-switch
                  v-else-if="field.type === 'boolean'"
                  v-model="form[field.prop]"
                  active-text="是"
                  inactive-text="否"
                  inline-prompt
                  @change="clearFieldError(field.prop)"
                />
                <el-input v-else-if="field.type === 'textarea'" v-model="form[field.prop]" type="textarea" :rows="3" @input="clearFieldError(field.prop)" />
                <el-input v-else v-model="form[field.prop]" :disabled="Boolean(editing?.id && field.readonlyOnEdit)" @input="clearFieldError(field.prop)" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" :disabled="saving" @click="save">保存</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import { computed, onMounted, reactive, ref, useSlots, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { list, get, create, update, remove } from '@/api/crud'
import { canReadField } from '@/permission/field'
import StatusTag from './StatusTag.vue'
import MoneyText from './MoneyText.vue'
import { productAliases } from '@/product/productSearch'
import { usePageAutoRefresh } from '@/composables/usePageAutoRefresh'

interface Col {
  prop: string
  label: string
  type?: string
  width?: number
  minWidth?: number
  formatter?: (value: any, row: any) => string
}
interface Field {
  prop: string
  label: string
  type?: string
  span?: number
  precision?: number
  options?: any[]
  defaultValue?: any
  omitWhenBlank?: boolean
  readonlyOnEdit?: boolean
  suggestionEndpoint?: string
  valueProp?: string
  codeProp?: string
  codeTargetProp?: string
  idTargetProp?: string
  labelProp?: string
  required?: boolean
  visibleWhen?: (form: Record<string, any>) => boolean
}

const props = defineProps<{
  title: string
  description?: string
  endpoint: string
  columns: Col[]
  fields: Field[]
  readonly?: boolean
  allowCreate?: boolean
  labelWidth?: string
  loadDetailOnEdit?: boolean
  preparePayload?: (payload: Record<string, any>, form: Record<string, any>, editing: any) => Record<string, any>
  saveSuccessMessage?: string
  editLabel?: string
}>()

const rows = ref<any[]>([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const dialogVisible = ref(false)
const editing = ref<any>(null)
const saving = ref(false)
const form = reactive<any>({})
const fieldErrors = reactive<Record<string, string>>({})
const suggestionCache = reactive<Record<string, any[]>>({})
const monthDayValues = reactive<Record<string, { month?: number; day?: number }>>({})
const slots = useSlots()

const visibleColumns = computed(() => props.columns.filter(c => canReadField(c.prop)))
const visibleFields = computed(() => props.fields.filter(f => canReadField(f.prop) && (!f.visibleWhen || f.visibleWhen(form))))
const canCreate = computed(() => !props.readonly || props.allowCreate)
const actionWidth = computed(() => props.readonly && slots.rowActions ? 150 : 150)

const filteredRows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter(row => JSON.stringify(row).toLowerCase().includes(k))
})
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

function rowIndex(index: number) {
  return (currentPage.value - 1) * pageSize.value + index + 1
}

async function load() {
  rows.value = await list<any>(props.endpoint)
  const lastPage = Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value))
  currentPage.value = Math.min(currentPage.value, lastPage)
}

async function loadSuggestions(field: Field) {
  if (!field.suggestionEndpoint || suggestionCache[field.suggestionEndpoint]) return
  suggestionCache[field.suggestionEndpoint] = await list<any>(field.suggestionEndpoint)
}

function formatValue(value: any) {
  if (typeof value === 'boolean') return value ? '是' : '否'
  return value ?? '-'
}

function formatCellValue(col: Col, row: any) {
  if (col.formatter) return col.formatter(row[col.prop], row)
  return formatValue(row[col.prop])
}

function columnWidth(col: Col) {
  if (col.width || col.minWidth) return col.width || col.minWidth
  return 150
}

function resetForm(row?: any) {
  Object.keys(form).forEach(k => delete form[k])
  Object.keys(monthDayValues).forEach(k => delete monthDayValues[k])
  clearFieldErrors()
  // Preserve row-level data used by dialog slots, such as customer contacts,
  // without letting edits mutate the table row before the user saves.
  if (row) {
    for (const [key, value] of Object.entries(row)) {
      form[key] = Array.isArray(value)
        ? value.map(item => item && typeof item === 'object' ? { ...item } : item)
        : value
    }
  }
  for (const field of props.fields) {
    const hasDefault = Object.prototype.hasOwnProperty.call(field, 'defaultValue')
    form[field.prop] = row?.[field.prop] ?? (hasDefault ? field.defaultValue : field.type === 'boolean' ? true : undefined)
    if (field.type === 'monthDay') {
      monthDayValues[field.prop] = parseMonthDay(form[field.prop])
    }
  }
}

function openCreate() {
  editing.value = null
  resetForm()
  void loadAutocompleteSources()
  dialogVisible.value = true
}

async function openEdit(row: any) {
  let source = row
  if (props.loadDetailOnEdit && row?.id) {
    source = await get<any>(props.endpoint, row.id)
  }
  editing.value = source
  resetForm(source)
  void loadAutocompleteSources()
  dialogVisible.value = true
}

async function save() {
  syncMonthDayFields()
  if (!validateRequiredFields()) return
  saving.value = true
  try {
    const basePayload = buildPayload()
    const payload = props.preparePayload
      ? props.preparePayload(basePayload, form, editing.value)
      : basePayload
    let saved: any
    if (editing.value?.id) {
      saved = await update(props.endpoint, editing.value.id, payload)
      const index = rows.value.findIndex(row => row.id === editing.value.id)
      if (index >= 0) rows.value.splice(index, 1, saved)
    } else {
      saved = await create(props.endpoint, payload)
      rows.value.unshift(saved)
    }
    ElMessage.success(props.saveSuccessMessage || '保存成功')
    clearFieldErrors()
    dialogVisible.value = false
  } finally {
    saving.value = false
  }
}

function isBlank(value: any) {
  return value === undefined || value === null || (typeof value === 'string' && value.trim() === '')
}

function validateRequiredFields() {
  clearFieldErrors()
  const missing = visibleFields.value.filter(field => field.required && isBlank(form[field.prop]))
  if (!missing.length) return true
  for (const field of missing) fieldErrors[field.prop] = `${field.label}未填写`
  ElMessage.error(missing.length === 1 ? `${missing[0].label}未填写` : `请填写必填项：${missing.map(field => field.label).join('、')}`)
  return false
}

function clearFieldError(prop: string) {
  if (fieldErrors[prop]) delete fieldErrors[prop]
}

function clearFieldErrors() {
  Object.keys(fieldErrors).forEach(key => delete fieldErrors[key])
}

function buildPayload() {
  const payload: Record<string, any> = {}
  for (const field of props.fields) {
    const value = form[field.prop]
    if (field.omitWhenBlank && (value === undefined || value === null || value === '')) continue
    payload[field.prop] = value
  }
  return payload
}

async function removeRow(row: any) {
  await remove(props.endpoint, row.id)
  rows.value = rows.value.filter(item => item.id !== row.id)
  ElMessage.success('删除成功')
}

async function loadAutocompleteSources() {
  await Promise.all(visibleFields.value.filter(f => f.type === 'autocomplete').map(loadSuggestions))
}

function parseMonthDay(value: any) {
  const matched = String(value || '').match(/(\d{1,2})\s*月\s*(\d{1,2})\s*日/)
  return {
    month: matched ? Number(matched[1]) : undefined,
    day: matched ? Number(matched[2]) : undefined
  }
}

function syncMonthDayFields() {
  for (const field of props.fields) {
    if (field.type !== 'monthDay') continue
    const value = monthDayValues[field.prop]
    form[field.prop] = value?.month && value?.day ? `${value.month}月${value.day}日` : undefined
  }
}

function fetchSuggestions(field: Field, query: string, cb: (items: any[]) => void) {
  const endpoint = field.suggestionEndpoint
  if (!endpoint) {
    cb([])
    return
  }
  const source = suggestionCache[endpoint] || []
  const key = query.trim().toLowerCase()
  if (!key) {
    cb([])
    return
  }
  const valueProp = field.valueProp || 'name'
  const codeProp = field.codeProp || 'code'
  const labelProp = field.labelProp || valueProp
  cb(source
    .filter(item => [item[labelProp], item[valueProp], item[codeProp], ...productAliases(item)]
      .some(value => String(value || '').toLowerCase().includes(key)))
    .slice(0, 8)
    .map(item => ({
      value: item[valueProp],
      sub: [item[codeProp], productAliases(item).length ? `别称：${productAliases(item).join('、')}` : ''].filter(Boolean).join('　'),
      raw: item
    })))
}

function selectSuggestion(field: Field, item: any) {
  form[field.prop] = item.value
  if (field.codeTargetProp && field.codeProp) {
    form[field.codeTargetProp] = item.raw?.[field.codeProp]
  }
  if (field.idTargetProp) {
    form[field.idTargetProp] = item.raw?.id
  }
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

function highlightSuggestion(value: string, query: string) {
  const safeValue = escapeHtml(String(value || ''))
  const key = String(query || '').trim()
  if (!key) return safeValue
  const escapedKey = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safeValue.replace(new RegExp(escapedKey, 'gi'), matched => `<span class="suggestion-hit">${matched}</span>`)
}

defineExpose({ load })
watch(dialogVisible, visible => {
  if (visible) void loadAutocompleteSources()
})
watch(keyword, () => { currentPage.value = 1 })
watch(pageSize, () => { currentPage.value = 1 })
onMounted(load)
usePageAutoRefresh(load, dialogVisible)
</script>

<style scoped>
.month-day-input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.month-day-input :deep(.el-input-number) {
  width: 100%;
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
