<template>
  <div class="page materials-page">
    <div class="page-heading">
      <div class="page-title">原料库存</div>
      <div class="muted">管理原材料库存，支持手机扫码登记出入库，自动汇总每日数量。</div>
    </div>
    <div class="filter-card">
      <div class="filter-inline">
        <div class="filter-field is-wide"><div class="filter-label">快速筛选</div><el-input v-model="keyword" placeholder="材料名称" clearable /></div>
        <div class="filter-field">
          <div class="filter-label">材料分类</div>
          <el-select v-model="category" multiple collapse-tags collapse-tags-tooltip :max-collapse-tags="1" placeholder="全部分类" clearable @change="clearAllCategory">
            <el-option label="全部分类" value="all"><div class="material-filter-option"><el-checkbox :key="category.length === 0 ? 'all' : 'some'" :model-value="category.length === 0" tabindex="-1" />全部分类</div></el-option>
            <el-option v-for="item in categories" :key="item" :label="item" :value="item"><div class="material-filter-option"><el-checkbox :key="item + ':' + category.includes(item)" :model-value="category.includes(item)" tabindex="-1" />{{ item }}</div></el-option>
          </el-select>
        </div>
        <div class="filter-field">
          <div class="filter-label">状态</div>
          <el-select v-model="status" multiple collapse-tags collapse-tags-tooltip :max-collapse-tags="1" placeholder="全部状态" clearable @change="clearAllStatus">
            <el-option label="全部状态" value="all"><div class="material-filter-option"><el-checkbox :key="status.length === 0 ? 'all' : 'some'" :model-value="status.length === 0" tabindex="-1" />全部状态</div></el-option>
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value"><div class="material-filter-option"><el-checkbox :key="item.value + ':' + status.includes(item.value)" :model-value="status.includes(item.value)" tabindex="-1" />{{ item.label }}</div></el-option>
          </el-select>
        </div>
      </div>
    </div>
    <div class="table-card">
      <div class="table-actions"><div class="left-actions"><el-button type="primary" @click="openCreate">新增材料</el-button></div><div class="right-actions"><span class="table-count">共 {{ filteredRows.length }} 条</span><el-button :loading="loading" @click="load">刷新</el-button></div></div>
      <el-table v-loading="loading" :data="pagedRows" height="max(240px, calc(100vh - 440px))" border stripe>
        <el-table-column type="index" :index="(index: number) => (page - 1) * size + index + 1" label="#" width="50" fixed />
        <el-table-column prop="category" label="分类" width="75" fixed />
        <el-table-column prop="name" label="材料名称" min-width="200" fixed show-overflow-tooltip />
        <el-table-column label="单位" width="80"><template #default="{ row }">{{ row.unit || '待完善' }}</template></el-table-column>
        <el-table-column label="当前总数量" width="125" align="right"><template #default="{ row }"><strong :class="{ 'warning-qty': row.warning }">{{ quantity(row.currentQty) }}</strong></template></el-table-column>
        <el-table-column label="今日数量" align="center">
          <el-table-column label="入库" width="100" align="right"><template #default="{ row }">{{ quantity(row.today?.purchaseQty ?? 0) }}</template></el-table-column>
          <el-table-column label="出库 / 生产使用" width="105" align="right"><template #default="{ row }">{{ quantity(row.today?.productionQty ?? 0) }}</template></el-table-column>
          <el-table-column label="损耗" width="100" align="right"><template #default="{ row }">{{ quantity(row.today?.lossQty ?? 0) }}</template></el-table-column>
          <el-table-column label="总消耗" width="105" align="right"><template #default="{ row }">{{ quantity(Number(row.today?.productionQty || 0) + Number(row.today?.lossQty || 0)) }}</template></el-table-column>
        </el-table-column>
        <el-table-column label="预警值" width="110" align="right"><template #default="{ row }">{{ quantity(row.safetyStock) }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="!row.enabled ? 'info' : !row.ready ? 'warning' : row.warning ? 'danger' : 'success'" size="small">{{ !row.enabled ? '已停用' : !row.ready ? '待完善' : row.warning ? '提醒采购' : '库存正常' }}</el-tag><span v-if="row.ready && row.enabled && !row.today" class="not-recorded">今日未登记</span></template></el-table-column>
        <el-table-column label="操作" width="285" fixed="right">
          <template #default="{ row }">
            <div class="material-row-actions">
              <el-button link type="primary" :disabled="!row.ready || !row.enabled" @click="openDaily(row)">登记</el-button>
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="primary" @click="openQr(row)">二维码</el-button>
              <el-button link type="primary" @click="openEdit(row)">{{ row.ready ? '编辑' : '完善' }}</el-button>
              <el-dropdown trigger="click" @command="(command: string) => moreAction(command, row)">
                <el-button link type="primary">更多</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="adjust" :disabled="!row.ready || !row.enabled">库存调整</el-dropdown-item>
                    <el-dropdown-item command="delete" :disabled="row.unitLocked">删除无记录材料</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="PAGE_SIZE_OPTIONS" :total="filteredRows.length" layout="total, sizes, prev, pager, next, jumper" /></div>
    </div>

    <el-dialog v-model="qrVisible" title="原料出入库二维码" width="min(480px, 94vw)" destroy-on-close>
      <div v-loading="qrLoading" class="material-qr" ref="qrRef">
        <h2>{{ qrMaterial?.name }}</h2>
        <p class="muted">单位：{{ qrMaterial?.unit || '待完善' }}</p>
        <qrcode-vue v-if="qrUrl" :value="qrUrl" :size="240" :margin="2" level="M" render-as="canvas" />
        <p>手机扫一扫 · 登记入库 / 出库</p>
        <el-alert v-if="!qrMaterial?.ready || !qrMaterial?.enabled" title="该原料尚未完善或已停用，完善并启用后方可扫码登记。" type="warning" :closable="false" />
        <p class="muted">此二维码固定不变，可长期张贴在对应原料处。</p>
      </div>
      <template #footer><el-button :disabled="!qrUrl || qrLoading" @click="printQr">打印标签</el-button><el-button :disabled="!qrUrl || qrLoading" @click="downloadQr">下载标签</el-button></template>
    </el-dialog>

    <el-dialog v-model="editorVisible" :title="form.id ? '编辑材料' : '新增材料'" width="720px" destroy-on-close>
      <el-alert v-if="form.id && !form.hasStock" title="请补充单位、初始数量与预警值，完成后即可登记采购和消耗。" type="info" :closable="false" show-icon class="dialog-alert" />
      <el-form label-width="110px"><el-row :gutter="16">
        <el-col :span="12"><el-form-item label="材料分类" required><el-select v-model="form.category" filterable allow-create default-first-option><el-option v-for="item in categories" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="材料名称" required><el-input v-model="form.name" maxlength="200" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="单位" required><el-select v-model="form.unit" filterable allow-create default-first-option placeholder="选择或输入单位"><el-option v-for="item in materialUnits" :key="item" :label="item" :value="item" /></el-select><span v-if="form.id && form.hasStock" class="unit-edit-hint">修改单位不会自动换算库存、预警值或历史数量。</span></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="预警值" required><el-input-number v-model="form.safetyStock" :min="0" :precision="3" :max="maxQty" controls-position="right" /></el-form-item></el-col>
        <el-col v-if="!form.hasStock" :span="12"><el-form-item label="初始数量" required><el-input-number v-model="form.initialQty" :min="0" :precision="3" :max="maxQty" controls-position="right" /></el-form-item></el-col>
        <el-col v-else :span="12"><el-form-item label="当前数量"><span>{{ quantity(form.currentQty) }} {{ form.unit }}（通过库存调整修改）</span></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item></el-col>
        <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item></el-col>
      </el-row></el-form>
      <template #footer><el-button @click="editorVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMaterial">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="dailyVisible" title="登记每日数量" width="620px" destroy-on-close>
      <div class="entry-material"><strong>{{ dailyMaterial?.name }}</strong><span>当前数量 {{ quantity(dailyMaterial?.currentQty) }} {{ dailyMaterial?.unit }}</span></div>
      <el-alert title="填写所选日期的总量。再次保存会替换该日总量，并按差额更新库存。" type="info" :closable="false" class="dialog-alert" />
      <el-form label-width="120px" v-loading="dailyLoading">
        <el-form-item label="登记日期" required><el-date-picker v-model="dailyDate" value-format="YYYY-MM-DD" :clearable="false" :disabled-date="futureDate" @change="loadDaily" /></el-form-item>
        <el-form-item label="采购入库量" required><el-input-number v-model="dailyForm.purchaseQty" :min="0" :precision="3" :max="maxQty" controls-position="right" /><span class="unit-label">{{ dailyMaterial?.unit }}</span></el-form-item>
        <el-form-item label="生产使用量" required><el-input-number v-model="dailyForm.productionQty" :min="0" :precision="3" :max="maxQty" controls-position="right" /><span class="unit-label">{{ dailyMaterial?.unit }}</span></el-form-item>
        <el-form-item label="损耗量" required><el-input-number v-model="dailyForm.lossQty" :min="0" :precision="3" :max="maxQty" controls-position="right" /><span class="unit-label">{{ dailyMaterial?.unit }}</span></el-form-item>
        <el-form-item label="备注"><el-input v-model="dailyForm.remark" type="textarea" :rows="2" maxlength="1000" /></el-form-item>
      </el-form>
      <div class="entry-preview">当日总消耗 <strong>{{ quantity(Number(dailyForm.productionQty || 0) + Number(dailyForm.lossQty || 0)) }} {{ dailyMaterial?.unit }}</strong><span>保存后当前数量 <strong>{{ quantity(projectedStock) }} {{ dailyMaterial?.unit }}</strong></span></div>
      <template #footer><el-button @click="dailyVisible = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="dailyLoading || !dailyLoaded" @click="saveDaily">保存当日总量</el-button></template>
    </el-dialog>

    <el-dialog v-model="adjustVisible" title="库存调整" width="560px" destroy-on-close>
      <div class="entry-material"><strong>{{ adjustMaterial?.name }}</strong><span>当前数量 {{ quantity(adjustMaterial?.currentQty) }} {{ adjustMaterial?.unit }}</span></div>
      <el-form label-width="110px"><el-form-item label="调整后数量" required><el-input-number v-model="adjustForm.targetQty" :min="0" :precision="3" :max="maxQty" controls-position="right" /><span class="unit-label">{{ adjustMaterial?.unit }}</span></el-form-item><el-form-item label="调整原因" required><el-input v-model="adjustForm.reason" type="textarea" :rows="3" maxlength="1000" placeholder="例如：盘点后修正库存" /></el-form-item></el-form>
      <template #footer><el-button @click="adjustVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveAdjustment">保存调整</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="`${detailMaterial?.name || '材料'} · 详情`" width="min(1120px, 95vw)" top="4vh" class="material-detail-dialog" style="max-height: 92vh; overflow-y: auto" @opened="renderTrend" @closed="disposeTrend">
      <div v-loading="detailLoading">
        <div class="detail-summary"><div><span>当前总数量</span><strong :class="{ 'warning-qty': detailMaterial?.warning }">{{ quantity(detailMaterial?.currentQty) }} {{ detailMaterial?.unit || '' }}</strong></div><div><span>预警值</span><strong>{{ quantity(detailMaterial?.safetyStock) }} {{ detailMaterial?.unit || '' }}</strong></div><div><span>材料分类</span><strong>{{ detailMaterial?.category || '未分类' }}</strong></div><div><span>状态</span><strong>{{ !detailMaterial?.enabled ? '已停用' : !detailMaterial?.ready ? '待完善' : detailMaterial?.warning ? '提醒采购' : '库存正常' }}</strong></div></div>
        <div class="trend-heading"><strong>每日消耗趋势</strong><span>最近30日 · 单位：{{ detailMaterial?.unit || '待完善' }}</span><el-button link type="primary" :disabled="!detailMaterial?.ready || !detailMaterial?.enabled" @click="detailMaterial && openDaily(detailMaterial)">登记今日数量</el-button></div>
        <div ref="chartRef" class="material-chart" role="img" aria-label="近30日生产使用、损耗及总消耗折线图"></div>
        <div class="detail-filters"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="业务开始日期" end-placeholder="业务结束日期" :disabled-date="futureDate" @change="resetDetailPages" /><el-button @click="refreshDetail">刷新</el-button><span class="muted">流水长期保留；更正按差额追加记录。</span></div>
        <el-tabs v-model="detailTab">
          <el-tab-pane label="每日记录" name="daily"><el-table :data="dailyRows" border stripe height="245"><el-table-column prop="businessDate" label="业务日期" width="115" /><el-table-column label="采购入库" width="105" align="right"><template #default="{ row }">{{ quantity(row.purchaseQty) }}</template></el-table-column><el-table-column label="出库 / 生产使用" width="105" align="right"><template #default="{ row }">{{ quantity(row.productionQty) }}</template></el-table-column><el-table-column label="损耗" width="95" align="right"><template #default="{ row }">{{ quantity(row.lossQty) }}</template></el-table-column><el-table-column label="总消耗" width="105" align="right"><template #default="{ row }">{{ quantity(Number(row.productionQty) + Number(row.lossQty)) }}</template></el-table-column><el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip /><el-table-column prop="updatedBy" label="最后操作人" width="170" /><el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" :disabled="!detailMaterial?.enabled" @click="detailMaterial && openDaily(detailMaterial, row.businessDate)">更正</el-button></template></el-table-column></el-table><div class="table-pagination"><el-pagination v-model:current-page="dailyPage" :page-size="DEFAULT_PAGE_SIZE" :total="dailyTotal" layout="total, prev, pager, next" @current-change="loadDetailTables" /></div></el-tab-pane>
          <el-tab-pane label="数量流水" name="ledger"><el-table :data="ledgerRows" border stripe height="245"><el-table-column label="业务日期" width="115"><template #default="{ row }">{{ row.businessDate || row.createdAt?.slice(0, 10) }}</template></el-table-column><el-table-column label="登记时间" width="170"><template #default="{ row }">{{ row.createdAt?.slice(0, 19).replace('T', ' ') }}</template></el-table-column><el-table-column label="类型" width="130"><template #default="{ row }">{{ movementLabel(row) }}</template></el-table-column><el-table-column label="变化数量" width="110" align="right"><template #default="{ row }"><span :class="movementDelta(row) < 0 ? 'warning-qty' : 'inbound-qty'">{{ movementDelta(row) > 0 ? '+' : '' }}{{ quantity(movementDelta(row)) }}</span></template></el-table-column><el-table-column label="变化前" width="100" align="right"><template #default="{ row }">{{ quantity(row.beforeQty) }}</template></el-table-column><el-table-column label="变化后" width="100" align="right"><template #default="{ row }">{{ quantity(row.afterQty) }}</template></el-table-column><el-table-column label="操作人" width="165"><template #default="{ row }">{{ row.operator || row.createdBy || '-' }}<span v-if="row.operatorUsername" class="operator-account">账户：{{ row.operatorUsername }}</span></template></el-table-column><el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip /></el-table><div class="table-pagination"><el-pagination v-model:current-page="ledgerPage" :page-size="DEFAULT_PAGE_SIZE" :total="ledgerTotal" layout="total, prev, pager, next" @current-change="loadDetailTables" /></div></el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '@/config/pagination'
import QrcodeVue from 'qrcode.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { init, use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { materialApi, materialCategories, materialUnits, quantity, shanghaiToday, type MaterialRow, type DailyRecord, type Movement, type TrendPoint } from '@/material/api'
use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const route = useRoute(), router = useRouter()
const maxQty = 999999999999
const rows = ref<MaterialRow[]>([]), keyword = ref(''), category = ref<string[]>([]), status = ref<string[]>([])
const statusOptions = [{ label: '待采购', value: 'warning' }, { label: '待完善', value: 'pending' }, { label: '启用', value: 'enabled' }, { label: '停用', value: 'disabled' }]
function clearAllCategory(values: string[]) { if (values.includes('all')) category.value = [] }
function clearAllStatus(values: string[]) { if (values.includes('all')) status.value = [] }
const page = ref(1), size = ref(DEFAULT_PAGE_SIZE), loading = ref(false), saving = ref(false)
const today = ref(shanghaiToday())
const categories = computed(() => [...new Set([...materialCategories, ...rows.value.map(r => r.category).filter(Boolean)])])
const filteredRows = computed(() => rows.value.filter(r => {
  const key = keyword.value.trim().toLowerCase()
  return (!key || r.name.toLowerCase().includes(key)) && (!category.value.length || category.value.includes(r.category))
    && (!status.value.length || status.value.some(value => (value === 'warning' && r.warning) || (value === 'pending' && !r.ready) || (value === 'enabled' && r.enabled) || (value === 'disabled' && !r.enabled)))
}))
const pagedRows = computed(() => filteredRows.value.slice((page.value - 1) * size.value, page.value * size.value))
watch([keyword, category, status, size], () => { page.value = 1 }, { deep: true })
const qrVisible = ref(false), qrLoading = ref(false), qrMaterial = ref<MaterialRow>(), qrUrl = ref(''), qrRef = ref<HTMLElement>()
async function openQr(row: MaterialRow) {
  qrMaterial.value = row; qrUrl.value = ''; qrVisible.value = true; qrLoading.value = true
  try {
    const result = await materialApi.scanCode(row.id)
    qrUrl.value = new URL(`/worker/material/${result.token}`, window.location.origin).href
  } finally { qrLoading.value = false }
}
function qrLabel() {
  const qr = qrRef.value?.querySelector('canvas')
  if (!qr) return
  const canvas = document.createElement('canvas'); canvas.width = 720; canvas.height = 860
  const ctx = canvas.getContext('2d')!
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, 720, 860); ctx.fillStyle = '#19324d'; ctx.textAlign = 'center'
  ctx.font = 'bold 32px sans-serif'; ctx.fillText(qrMaterial.value?.name || '', 360, 75, 660)
  ctx.font = '24px sans-serif'; ctx.fillText(`单位：${qrMaterial.value?.unit || '待完善'}`, 360, 125)
  ctx.imageSmoothingEnabled = false; ctx.drawImage(qr, 60, 165, 600, 600)
  ctx.font = '26px sans-serif'; ctx.fillText('手机扫一扫 · 登记入库 / 出库', 360, 815)
  return canvas.toDataURL('image/png')
}
function downloadQr() {
  const url = qrLabel(); if (!url) return
  const a = document.createElement('a'); a.href = url; a.download = `${qrMaterial.value?.name || '原料'}-出入库二维码.png`; a.click()
}
function printQr() {
  const url = qrLabel(); if (!url) return
  const win = window.open('', '_blank', 'width=600,height=760'); if (!win) { ElMessage.warning('请允许弹出打印窗口'); return }
  win.opener = null; win.document.title = `${qrMaterial.value?.name || '原料'}二维码`
  const style = win.document.createElement('style'); style.textContent = 'body{margin:0;text-align:center}img{width:90mm;max-width:100%}@page{margin:10mm}'
  win.document.head.append(style)
  const img = win.document.createElement('img'); img.alt = '原料出入库二维码'; img.onload = () => { win.focus(); win.print() }; img.src = url; win.document.body.append(img)
}
const editorVisible = ref(false)
function blankForm() { return { id: undefined as number | undefined, name: '', category: '', unit: '', safetyStock: undefined as number | undefined, initialQty: undefined as number | undefined, currentQty: undefined as number | undefined, enabled: true, remark: '', versionNo: undefined as number | undefined, stockVersion: undefined as number | undefined, hasStock: false, unitLocked: false } }
const form = reactive(blankForm())
async function load() {
  loading.value = true
  try { today.value = shanghaiToday(); rows.value = await materialApi.list(); page.value = Math.min(page.value, Math.max(1, Math.ceil(filteredRows.value.length / size.value))) }
  finally { loading.value = false }
}
function openCreate() { Object.assign(form, blankForm()); editorVisible.value = true }
async function openEdit(row: MaterialRow) {
  const live = await materialApi.get(row.id)
  Object.assign(form, blankForm(), live, { unit: live.unit || '', remark: live.remark || '', hasStock: live.currentQty != null })
  editorVisible.value = true
}
async function saveMaterial() {
  if (!form.name.trim() || !form.category.trim() || !form.unit.trim() || form.safetyStock == null || (!form.hasStock && form.initialQty == null)) { ElMessage.warning('请填写材料分类、名称、单位、预警值和初始数量'); return }
  saving.value = true
  try {
    const data = { name: form.name, category: form.category, unit: form.unit, safetyStock: form.safetyStock, initialQty: form.hasStock ? undefined : form.initialQty, enabled: form.enabled, remark: form.remark, versionNo: form.versionNo, stockVersion: form.stockVersion }
    if (form.id) await materialApi.update(form.id, data); else await materialApi.create(data)
    editorVisible.value = false; ElMessage.success('材料已保存'); await afterSave()
  } finally { saving.value = false }
}
async function moreAction(command: string, row: MaterialRow) {
  if (command === 'adjust') {
    adjustMaterial.value = await materialApi.get(row.id)
    adjustForm.targetQty = Number(adjustMaterial.value.currentQty); adjustForm.reason = ''; adjustVisible.value = true
  } else {
    try { await ElMessageBox.confirm(`删除“${row.name}”？已有数量记录的材料请在编辑中停用。`, '删除材料', { type: 'warning' }) }
    catch { return }
    await materialApi.remove(row.id); ElMessage.success('材料已删除'); await load()
  }
}

const dailyVisible = ref(false), dailyLoading = ref(false), dailyLoaded = ref(false), dailyDate = ref(today.value)
const dailyMaterial = ref<MaterialRow>(), previousRecord = ref<DailyRecord | null>(null)
const dailyForm = reactive({ purchaseQty: 0, productionQty: 0, lossQty: 0, remark: '', versionNo: -1 })
let dailySequence = 0
function futureDate(date: Date) { return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0') > shanghaiToday() }
async function openDaily(row: MaterialRow, date = shanghaiToday()) {
  dailyMaterial.value = row; dailyDate.value = date; dailyVisible.value = true; await loadDaily()
}
async function loadDaily() {
  if (!dailyMaterial.value || !dailyDate.value) return
  const sequence = ++dailySequence, id = dailyMaterial.value.id, date = dailyDate.value
  dailyLoading.value = true; dailyLoaded.value = false
  try {
    const [live, result] = await Promise.all([materialApi.get(id), materialApi.record(id, date)])
    if (sequence !== dailySequence) return
    dailyMaterial.value = live
    const record = result?.id ? result : null
    previousRecord.value = record
    Object.assign(dailyForm, { purchaseQty: Number(record?.purchaseQty || 0), productionQty: Number(record?.productionQty || 0), lossQty: Number(record?.lossQty || 0), remark: record?.remark || '', versionNo: record?.versionNo ?? -1 })
    dailyLoaded.value = true
  } finally { if (sequence === dailySequence) dailyLoading.value = false }
}
const projectedStock = computed(() => {
  const previous = previousRecord.value
  return Number(dailyMaterial.value?.currentQty || 0) + Number(dailyForm.purchaseQty || 0) - Number(previous?.purchaseQty || 0)
    - Number(dailyForm.productionQty || 0) + Number(previous?.productionQty || 0) - Number(dailyForm.lossQty || 0) + Number(previous?.lossQty || 0)
})
async function saveDaily() {
  if (!dailyMaterial.value || !dailyLoaded.value) return
  if ([dailyForm.purchaseQty, dailyForm.productionQty, dailyForm.lossQty].some(value => value == null)) { ElMessage.warning('请填写三项数量，无发生量填写0'); return }
  saving.value = true
  try { await materialApi.saveDaily(dailyMaterial.value.id, dailyDate.value, { ...dailyForm, stockVersion: dailyMaterial.value.stockVersion }); dailyVisible.value = false; ElMessage.success('每日数量已保存'); await afterSave() }
  finally { saving.value = false }
}
const adjustVisible = ref(false), adjustMaterial = ref<MaterialRow>()
const adjustForm = reactive({ targetQty: undefined as number | undefined, reason: '' })
async function saveAdjustment() {
  if (!adjustMaterial.value) return
  if (adjustForm.targetQty == null || !adjustForm.reason.trim()) { ElMessage.warning('请填写调整后数量和调整原因'); return }
  saving.value = true
  try { await materialApi.adjust(adjustMaterial.value.id, { ...adjustForm, stockVersion: adjustMaterial.value.stockVersion }); adjustVisible.value = false; ElMessage.success('库存已调整'); await afterSave() }
  finally { saving.value = false }
}
async function afterSave() { await load(); if (detailVisible.value) await refreshDetail() }

const detailVisible = ref(false), detailLoading = ref(false), detailMaterial = ref<MaterialRow>(), detailTab = ref('daily')
const chartRef = ref<HTMLDivElement>(), trendRows = ref<TrendPoint[]>([])
let chart: ReturnType<typeof init> | null = null, observer: ResizeObserver | null = null
const dateRange = ref<string[] | null>(null), dailyPage = ref(1), ledgerPage = ref(1)
const dailyRows = ref<DailyRecord[]>([]), ledgerRows = ref<Movement[]>([]), dailyTotal = ref(0), ledgerTotal = ref(0)
let detailSequence = 0, detailTableSequence = 0
async function openDetail(row: MaterialRow) {
  detailMaterial.value = row; detailVisible.value = true; dateRange.value = null; dailyPage.value = 1; ledgerPage.value = 1; detailTab.value = 'daily'
  await refreshDetail()
}
async function refreshDetail() {
  if (!detailMaterial.value) return
  const sequence = ++detailSequence, id = detailMaterial.value.id
  detailLoading.value = true
  try {
    const [material, trend] = await Promise.all([materialApi.get(id), materialApi.trend(id)])
    if (sequence !== detailSequence) return
    detailMaterial.value = material; trendRows.value = trend
    await loadDetailTables(); await nextTick(); if (detailVisible.value) renderTrend()
  } finally { if (sequence === detailSequence) detailLoading.value = false }
}
async function loadDetailTables() {
  if (!detailMaterial.value) return
  const id = detailMaterial.value.id, sequence = detailSequence, tableSequence = ++detailTableSequence
  const params = { start: dateRange.value?.[0], end: dateRange.value?.[1], size: DEFAULT_PAGE_SIZE }
  const tab = detailTab.value
  const result = tab === 'daily'
    ? await materialApi.records(id, { ...params, page: dailyPage.value })
    : await materialApi.ledger(id, { ...params, page: ledgerPage.value })
  if (sequence !== detailSequence || tableSequence !== detailTableSequence || id !== detailMaterial.value?.id || tab !== detailTab.value) return
  if (tab === 'daily') { dailyRows.value = result.items as DailyRecord[]; dailyTotal.value = result.total }
  else { ledgerRows.value = result.items as Movement[]; ledgerTotal.value = result.total }
}
watch(detailTab, () => { if (detailVisible.value) void loadDetailTables() })
function resetDetailPages() { dailyPage.value = 1; ledgerPage.value = 1; void loadDetailTables() }
function movementDelta(row: Movement) { return Number((Number(row.afterQty) - Number(row.beforeQty)).toFixed(3)) }
function movementLabel(row: Movement) {
  const labels: Record<string, string> = { INITIAL: '初始数量', PURCHASE: '采购入库', PRODUCTION: '生产使用', LOSS: '损耗', ADJUST: '库存调整', DAILY_NOTE: '每日登记' }
  const legacy: Record<string, string> = { IN: '历史入库', OUT: '历史出库', ADJUST: '历史调整' }
  return (labels[row.materialMovementType || ''] || legacy[row.txnType] || row.txnType) + (row.sourceType === 'MATERIAL_DAILY_CORRECTION' ? '（更正）' : '')
}
function renderTrend() {
  if (!chartRef.value || !detailVisible.value) return
  if (!chart) { chart = init(chartRef.value); observer = new ResizeObserver(() => chart?.resize()); observer.observe(chartRef.value) }
  chart.setOption({ color: ['#4674ea', '#e89842', '#39a680'], tooltip: { trigger: 'axis', valueFormatter: (value: number) => `${quantity(value)} ${detailMaterial.value?.unit || ''}` }, legend: { top: 4 }, grid: { top: 45, left: 55, right: 20, bottom: 30 }, xAxis: { type: 'category', data: trendRows.value.map(r => r.date), axisLabel: { formatter: (value: string) => value.slice(5) } }, yAxis: { type: 'value', min: 0 }, series: [{ name: '生产使用', key: 'productionQty' }, { name: '损耗', key: 'lossQty' }, { name: '总消耗', key: 'totalQty' }].map(item => ({ name: item.name, type: 'line', smooth: false, showSymbol: false, data: trendRows.value.map(r => Number(r[item.key as keyof TrendPoint])) })) }, true)
  chart.resize()
}
function disposeTrend() { detailSequence++; observer?.disconnect(); observer = null; chart?.dispose(); chart = null }
async function openQueryDetail() {
  const id = Number(route.query.materialId)
  if (!id) return
  const row = rows.value.find(r => r.id === id)
  if (row) await openDetail(row)
  await router.replace({ path: route.path, query: {} })
}
watch(() => route.query.materialId, () => { if (!loading.value) void openQueryDetail() })
function refreshOnFocus() { if (!document.hidden && !loading.value && !editorVisible.value && !dailyVisible.value && !adjustVisible.value) void afterSave().catch(() => {}) }
let dateTimer: ReturnType<typeof setInterval>
onMounted(async () => {
  await load(); await openQueryDetail(); window.addEventListener('focus', refreshOnFocus)
  dateTimer = setInterval(refreshOnFocus, 10000)
  document.addEventListener('visibilitychange', refreshOnFocus)
})
onBeforeUnmount(() => { dailySequence++; disposeTrend(); clearInterval(dateTimer); window.removeEventListener('focus', refreshOnFocus); document.removeEventListener('visibilitychange', refreshOnFocus) })
</script>

<style scoped>
.material-qr { text-align:center; min-height:320px; }
.material-qr h2 { margin:0 0 10px; }
.material-qr canvas { max-width:100%; }
.warning-qty { color:#d85849; }
.material-filter-option { display:flex; align-items:center; gap:10px; }
.material-filter-option :deep(.el-checkbox) { pointer-events:none; }
.material-row-actions { display:inline-flex; align-items:center; gap:16px; white-space:nowrap; }
.material-row-actions :deep(.el-button) { margin:0; padding:2px 0; height:24px; font-size:14px; line-height:20px; font-weight:600; vertical-align:middle; }
.material-row-actions :deep(.el-dropdown) { display:inline-flex; align-items:center; line-height:20px; vertical-align:middle; }
.not-recorded { display:block; color:#8b97a7; font-size:11px; margin-top:3px; }
.entry-material { display:flex; justify-content:space-between; gap:20px; margin-bottom:18px; color:#64748b; }
.entry-material strong { color:#263b55; }
.dialog-alert { margin-bottom:20px; }
.unit-label { margin-left:12px; color:#64748b; }
.unit-edit-hint { width:100%; margin-top:6px; font-size:12px; line-height:1.6; color:#8794a8; }
.entry-preview { padding:15px; background:#f4f7fc; border-radius:8px; font-size:13px; }
.entry-preview span { display:block; margin-top:8px; }
.detail-summary { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-bottom:20px; }
.detail-summary div { background:#f6f8fc; padding:14px; border-radius:8px; }
.detail-summary span { display:block; color:#8794a8; font-size:12px; margin-bottom:8px; }
.detail-summary strong { font-size:18px; }
.trend-heading { display:flex; align-items:center; gap:15px; }
.trend-heading span { color:#8794a8; font-size:12px; }
.trend-heading .el-button { margin-left:auto; }
.material-chart { height:230px; margin:12px 0; }
.detail-filters { display:flex; align-items:center; gap:12px; margin-bottom:12px; }
.detail-filters :deep(.el-date-editor) { flex:0 0 285px; }
.operator-account { display:block; font-size:12px; color:#8794a8; }
.inbound-qty { color:#279477; }
:deep(.el-input-number),:deep(.el-form-item .el-select) { width:100%; }
:deep(.unit-label) { flex-shrink:0; }
:deep(.el-form-item__content > .el-input-number) { width:190px; }
@media(max-width:900px) { .detail-summary { grid-template-columns:repeat(2,1fr); } .detail-filters { flex-wrap:wrap; } }
</style>
