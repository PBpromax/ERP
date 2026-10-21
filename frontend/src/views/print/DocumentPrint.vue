<template>
  <div class="print-page">
    <PrintToolbar />
    <div class="doc-header">
      <h1>{{ title }}</h1>
      <div class="muted">{{ subtitle }}</div>
    </div>

    <el-descriptions :column="2" border>
      <el-descriptions-item v-for="item in headerFields" :key="item.label" :label="item.label">
        <MoneyText v-if="item.money" :value="item.value" field="amount" />
        <span v-else>{{ formatValue(item.value) }}</span>
      </el-descriptions-item>
    </el-descriptions>

    <el-table :data="items" border style="margin-top:18px">
      <el-table-column v-for="col in itemColumns" :key="col.prop" :prop="col.prop" :label="col.label">
        <template #default="{ row }">
          <MoneyText v-if="col.money" :value="row[col.prop]" :field="col.prop" />
          <span v-else>{{ formatValue(row[col.prop]) }}</span>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="type === 'delivery'" class="qr">
      <qrcode-vue :value="header.qrCodeContent || header.deliveryNo || ''" :size="120" />
      <div>扫码查看送货单：{{ header.qrCodeContent }}</div>
    </div>

    <div class="sign-row">
      <span>制单：</span>
      <span>审核：</span>
      <span>签收：</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import QrcodeVue from 'qrcode.vue'
import { get } from '@/api/crud'
import MoneyText from '@/components/MoneyText.vue'
import PrintToolbar from '@/components/PrintToolbar.vue'

const route = useRoute()
const type = computed(() => String(route.params.type))
const id = computed(() => String(route.params.id))
const detail = ref<any>({})

const header = computed(() => {
  if (type.value === 'quotation') return detail.value.quotation || {}
  if (type.value === 'delivery') return detail.value.delivery || {}
  if (type.value === 'statement') return detail.value.statement || {}
  return {}
})
const items = computed(() => detail.value.items || [])
const title = computed(() => type.value === 'quotation' ? '报价单' : type.value === 'delivery' ? '送货单' : '账单')
const subtitle = computed(() => header.value.quoteNo || header.value.deliveryNo || header.value.statementNo || '')

function formatValue(value: any) {
  if (typeof value === 'boolean') return value ? '是' : '否'
  return value ?? '-'
}

const headerFields = computed(() => {
  const h = header.value
  if (type.value === 'quotation') {
    return [
      { label: '报价单号', value: h.quoteNo }, { label: '客户', value: h.customerName },
      { label: '报价日期', value: h.quoteDate }, { label: '有效期', value: h.validUntil },
      { label: '总金额', value: h.totalAmount, money: true }, { label: '状态', value: h.status }
    ]
  }
  if (type.value === 'delivery') {
    return [
      { label: '送货单号', value: h.deliveryNo }, { label: '订单号', value: h.orderNo },
      { label: '客户', value: h.customerName }, { label: '送货日期', value: h.deliveryDate },
      { label: '金额', value: h.printPrice ? h.totalAmount : '无价版', money: !!h.printPrice }, { label: '状态', value: h.status }
    ]
  }
  return [
    { label: '账单号', value: h.statementNo }, { label: '客户', value: h.customerName },
    { label: '月份', value: h.month }, { label: '应收金额', value: h.receivableAmount, money: true },
    { label: '已开票', value: h.invoicedAmount, money: true }, { label: '欠款余额', value: h.arrearsAmount, money: true }
  ]
})

const itemColumns = computed(() => {
  if (type.value === 'statement') {
    return [
      { prop: 'deliveryNo', label: '送货单' }, { prop: 'orderNo', label: '订单号' },
      { prop: 'productName', label: '产品' }, { prop: 'qty', label: '数量' },
      { prop: 'amount', label: '金额', money: true }, { prop: 'deliveryDate', label: '发货日期' }
    ]
  }
  return [
    { prop: 'productName', label: '产品' }, { prop: 'spec', label: '规格' },
    { prop: 'unit', label: '单位' }, { prop: 'qty', label: '数量' },
    { prop: 'unitPrice', label: '单价', money: true }, { prop: 'amount', label: '金额', money: true }
  ]
})

async function load() {
  if (type.value === 'quotation') detail.value = await get('/sales/quotations', `${id.value}/detail`)
  if (type.value === 'delivery') detail.value = await get('/sales/deliveries', `${id.value}/detail`)
  if (type.value === 'statement') detail.value = await get('/finance/statements', `${id.value}/detail`)
}

onMounted(load)
</script>

<style scoped>
.doc-header {
  text-align: center;
  margin: 12px 0 22px;
}

h1 {
  letter-spacing: 6px;
}

.qr {
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.sign-row {
  display: flex;
  justify-content: space-between;
  margin-top: 60px;
  font-size: 16px;
}
</style>
