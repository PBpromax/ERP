<template>
  <div class="worker-page">
    <main class="worker-shell">
      <section class="worker-head">
        <div>
          <div class="worker-kicker">生产录入</div>
          <h1>{{ worker.workerName || '生产人员' }}</h1>
        </div>
      </section>

      <section class="entry-panel">
        <el-form :model="form" label-position="top">
          <el-form-item label="日期" required :error="formErrors.productionDate">
            <el-date-picker v-model="form.productionDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="产品" required :error="formErrors.semiFinishedCode">
            <el-autocomplete
              v-model="form.semiFinishedCode"
              :fetch-suggestions="stockSuggestions"
              value-key="value"
              clearable
              style="width: 100%"
              placeholder="输入产品编号搜索"
              @select="selectStock"
            >
              <template #default="{ item }">
                <div class="suggestion-main">{{ item.value }}</div>
                <div v-if="item.sub" class="suggestion-sub">{{ item.sub }}</div>
              </template>
            </el-autocomplete>
          </el-form-item>
          <el-form-item label="数量" required :error="formErrors.qty">
            <el-input-number v-model="form.qty" :min="0" :precision="3" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-button class="save-button" type="primary" :loading="saving" @click="saveRecord">保存</el-button>
        </el-form>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '@/api/http'
import { productAliases } from '@/product/productSearch'

const route = useRoute()
const token = computed(() => String(route.params.token || ''))
const worker = reactive<any>({})
const stocks = ref<any[]>([])
const saving = ref(false)
const form = reactive<any>({
  productionDate: new Date().toISOString().slice(0, 10),
  semiFinishedCode: '',
  qty: undefined
})
const formErrors = reactive<Record<string, string>>({})

watch(() => form.semiFinishedCode, value => {
  const stock = stocks.value.find(item => item.code === value)
  if (stock) selectStock({ raw: stock, value: stock.code })
})

async function loadWorker() {
  const data = await http.get<any, any>(`/production/records/worker-entry/${token.value}`)
  Object.assign(worker, data)
}

async function loadStocks() {
  stocks.value = await http.get<any[], any[]>(`/production/records/worker-entry/${token.value}/stocks`)
}

function stockSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim().toLowerCase()
  cb(stocks.value
    .filter(stock => {
      const text = [stock.code, stock.name, stock.spec, stock.planNo, stock.sourceOrderNo, ...productAliases(stock)].join(' ').toLowerCase()
      return !key || text.includes(key)
    })
    .slice(0, 20)
    .map(stock => ({ value: stock.code, sub: stockSuggestionSub(stock), raw: stock })))
}

function selectStock(item: any) {
  form.semiFinishedCode = item.raw.code
}

function stockSuggestionSub(stock: any) {
  return [
    stock.name ? `名称：${stock.name}` : '',
    stock.spec ? `规格：${stock.spec}` : '',
    productAliases(stock).length ? `别称：${productAliases(stock).join('、')}` : '',
    stock.assignedQty != null ? `计划数量：${formatQty(stock.assignedQty)}` : ''
  ].filter(Boolean).join('　')
}

function formatQty(value: any) {
  const number = Number(value || 0)
  if (!Number.isFinite(number)) return '0'
  return Number.isInteger(number) ? String(number) : String(number).replace(/\.?0+$/, '')
}

async function saveRecord() {
  Object.keys(formErrors).forEach(key => delete formErrors[key])
  if (!form.productionDate) {
    formErrors.productionDate = '日期未填写'
    ElMessage.error('日期未填写')
    return
  }
  if (!form.semiFinishedCode) {
    formErrors.semiFinishedCode = '产品未填写'
    ElMessage.error('产品未填写')
    return
  }
  if (!Number(form.qty) || Number(form.qty) <= 0) {
    formErrors.qty = '数量必须大于0'
    ElMessage.error('数量必须大于0')
    return
  }
  saving.value = true
  try {
    await http.post(`/production/records/worker-entry/${token.value}/items`, {
      productionDate: form.productionDate,
      semiFinishedCode: form.semiFinishedCode,
      qty: form.qty
    })
    ElMessage.success('已保存')
    form.semiFinishedCode = ''
    form.qty = undefined
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadWorker(), loadStocks()])
})
</script>

<style scoped>
.worker-page {
  min-height: 100vh;
  background: #f5f7fb;
  padding: 18px;
}

.worker-shell {
  margin: 0 auto;
  max-width: 620px;
}

.worker-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin-bottom: 14px;
}

.worker-kicker {
  color: var(--oms-sub);
  font-size: 13px;
  line-height: 20px;
}

h1 {
  color: var(--oms-heading);
  font-size: 28px;
  line-height: 36px;
  margin: 0;
}

.entry-panel {
  background: #fff;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  box-shadow: var(--oms-shadow);
  margin-bottom: 14px;
  padding: 16px;
}

.save-button {
  height: 44px;
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
</style>
