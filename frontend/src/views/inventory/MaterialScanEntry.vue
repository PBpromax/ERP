<template>
  <div class="scan-page">
    <main class="scan-shell" :class="{ 'selection-shell': selectionMode && canRegister }" v-loading="loading">
      <div class="scan-brand">原料库存<span v-if="previewMode" class="preview-badge">页面演示</span></div>
      <div v-if="user.isLoggedIn && user.user && !loading" class="employee-bar">
        <span>当前员工：<strong>{{ user.user.realName || user.user.username }}</strong><small>{{ user.user.username }}</small></span>
        <el-button link type="primary" :disabled="saving" @click="switchAccount">退出登录</el-button>
      </div>
      <section class="scan-card" :class="{ 'selection-card': selectionMode && canRegister }">
        <p v-if="loading" class="scan-hint">正在加载…</p>
        <template v-else-if="!user.isLoggedIn">
          <div class="scan-kicker">原料出入库登记</div><h1>员工登录</h1>
          <p class="login-description">请使用管理员为你开通的账号登录，登记将记录你的姓名和账户。</p>
          <el-form label-position="top" @submit.prevent="login">
            <el-form-item label="登录账户" required><el-input v-model="loginForm.username" placeholder="请输入员工账号" size="large" autocomplete="username" :disabled="signingIn" /></el-form-item>
            <el-form-item label="密码" required><el-input v-model="loginForm.password" placeholder="请输入密码" size="large" type="password" show-password autocomplete="current-password" :disabled="signingIn" /></el-form-item>
            <p v-if="loginError" class="error" role="alert">{{ loginError }}</p>
            <el-button native-type="submit" class="submit" type="primary" :loading="signingIn">登录后登记</el-button>
          </el-form>
        </template>
        <template v-else-if="!canRegister && !loadError">
          <h1>暂无登记权限</h1><p class="login-description">请联系管理员，在用户管理中为你的账号勾选“出库登记”或“入库登记”权限。</p>
          <el-button type="primary" @click="load">重新检查权限</el-button>
        </template>
        <template v-else-if="loadError">
          <h1>暂时无法登记</h1><p class="error" role="alert">{{ loadError }}</p>
          <el-button @click="load">重新加载</el-button>
        </template>
        <template v-else-if="selectionMode">
          <div class="selection-heading"><div><div class="scan-kicker">原料出入库登记</div><h1>选择原料</h1><p>点击原料，{{ permissionDescription }}。</p></div><span class="selection-count">共 {{ filteredMaterials.length }} 项</span></div>
          <el-input v-model="materialKeyword" class="material-search" placeholder="搜索原料名称或分类" clearable size="large" aria-label="搜索原料" />
          <div class="material-grid" aria-label="原料选择">
            <button v-for="item in filteredMaterials" :key="item.id" class="material-bubble" type="button" :disabled="!item.ready || !item.enabled" @click="selectMaterial(item.id)">
              <span class="bubble-category">{{ item.category || '原料' }}</span><strong>{{ item.name }}</strong><span class="bubble-unit">{{ !item.enabled ? '已停用' : !item.ready ? '资料待完善' : item.unit }}</span>
            </button>
          </div>
          <p v-if="!filteredMaterials.length" class="scan-hint">暂无匹配的原料</p>
          <p v-if="entryMaterials.some(item => !item.ready)" class="scan-hint">资料待完善的原料，请管理员先补充单位和初始库存等资料。</p>
        </template>
        <template v-else-if="material">
          <el-button v-if="route.params.materialId" class="back-to-materials" link type="primary" :disabled="saving || !!pending" @click="router.push('/worker/material-entry')">← 返回原料选择</el-button>
          <div class="scan-kicker">原料出入库登记</div>
          <h1>{{ material.name }}</h1>
          <p class="scan-unit">计量单位 <strong>{{ material.unit }}</strong></p>
          <template v-if="receipt">
            <div class="receipt" role="status"><div class="receipt-check">✓</div><h2>{{ previewMode ? '演示登记成功' : '登记成功' }}</h2><p>{{ receipt.direction === 'IN' ? '入库' : '出库' }} {{ receipt.qty }} {{ receipt.unit }}</p><p>{{ previewMode ? '本次仅为演示，未修改实际库存' : '已同步到原料库存' }}</p><p v-if="receipt.operator">操作人：{{ receipt.operator }}（{{ receipt.operatorUsername }}）</p></div>
            <el-button class="submit" type="primary" @click="nextEntry">继续登记</el-button>
          </template>
          <el-form v-else label-position="top" @submit.prevent="submit">
            <el-form-item label="登记类型" required>
              <div class="direction-options" role="group" aria-label="登记类型">
                <button v-if="canOut" type="button" :class="{ selected: direction === 'OUT' }" :disabled="saving || !!pending" :aria-pressed="direction === 'OUT'" @click="direction = 'OUT'">↗ 出库</button>
                <button v-if="canIn" type="button" :class="{ selected: direction === 'IN' }" :disabled="saving || !!pending" :aria-pressed="direction === 'IN'" @click="direction = 'IN'">↙ 入库</button>
              </div>
              <p v-if="!canIn || !canOut" class="direction-hint">当前账号仅可{{ canOut ? '出库' : '入库' }}登记</p>
            </el-form-item>
            <el-form-item label="数量" required :error="validation">
              <el-input v-model="qty" inputmode="decimal" placeholder="请输入数量" size="large" :disabled="saving || !!pending" aria-label="数量"><template #append>{{ material.unit }}</template></el-input>
            </el-form-item>
            <p v-if="error" class="error" role="alert">{{ error }}</p>
            <p v-if="pending && !saving" class="scan-hint">上次登记结果尚未确认，请点击重试。同一笔登记只会计入一次。</p>
            <el-button native-type="submit" class="submit" type="primary" :loading="saving">{{ pending && !saving ? '重试并确认登记' : '登记' }}</el-button>
            <p class="scan-hint">{{ previewMode ? '数量最多保留三位小数。此页仅供演示，不修改实际库存。' : '数量最多保留三位小数，登记后自动更新库存。' }}</p>
          </el-form>
        </template>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/api/http'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const canIn = computed(() => user.isLoggedIn && user.hasMenu('material-entry-in'))
const canOut = computed(() => user.isLoggedIn && user.hasMenu('material-entry-out'))
const canRegister = computed(() => canIn.value || canOut.value)
const permissionDescription = computed(() => canIn.value && canOut.value ? '登记入库或出库' : canOut.value ? '登记出库' : '登记入库')
function defaultDirection() { return canIn.value && !canOut.value ? 'IN' : canOut.value && !canIn.value ? 'OUT' : '' }
function directionAllowed(value: string) { return value === 'IN' ? canIn.value : value === 'OUT' && canOut.value }
const loginForm = reactive({ username: '', password: '' })
const signingIn = ref(false), loginError = ref('')
async function login() {
  if (signingIn.value) return
  loginError.value = ''
  if (!loginForm.username.trim() || !loginForm.password) { loginError.value = '请填写登录账户和密码'; return }
  signingIn.value = true
  try { await user.login(loginForm.username.trim(), loginForm.password); loginForm.password = ''; await load() }
  catch (ex) { loginError.value = message(ex) }
  finally { signingIn.value = false }
}
async function switchAccount() { if (saving.value) return; await user.logout(); await load() }
const previewMode = computed(() => route.meta.preview === true)
const selectionMode = computed(() => !previewMode.value && !route.params.token && !route.params.materialId)
interface EntryMaterial { id: number; name: string; category?: string; unit?: string; ready: boolean; enabled: boolean }
const entryMaterials = ref<EntryMaterial[]>([]), materialKeyword = ref('')
const filteredMaterials = computed(() => {
  const key = materialKeyword.value.trim().toLowerCase()
  return entryMaterials.value.filter(item => !key || `${item.name} ${item.category || ''}`.toLowerCase().includes(key))
})
function selectMaterial(id: number) { void router.push(`/worker/material-entry/${id}`) }
const entryApiPath = computed(() => route.params.materialId
  ? `/material-entry/materials/${encodeURIComponent(String(route.params.materialId))}`
  : `/material-entry/${encodeURIComponent(String(route.params.token))}`)
const material = ref<{ name: string; unit: string }>()
const loading = ref(true), saving = ref(false), loadError = ref(''), error = ref(''), validation = ref('')
const direction = ref(''), qty = ref('')
type Submission = { requestId: string; direction: string; qty: string }
type Receipt = { id: number; direction: string; qty: number; unit: string; businessDate: string; operator?: string; operatorUsername?: string }
const pending = ref<Submission>(), receipt = ref<Receipt>()
function storageKey() { return `oms:material-entry:${user.user?.id}:${route.params.materialId ? 'id:' + route.params.materialId : 'qr:' + route.params.token}` }
function requestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, x => x.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
function message(ex: any) { return ex?.response?.data?.message || ex?.message || '网络连接失败，请稍后重试' }
let loadSequence = 0
async function load() {
  const sequence = ++loadSequence
  loading.value = true; loadError.value = ''; material.value = undefined; receipt.value = undefined; pending.value = undefined; direction.value = ''; qty.value = ''; error.value = ''; validation.value = ''
  try {
    if (!user.isLoggedIn) return
    await user.loadProfile()
    if (sequence !== loadSequence || !canRegister.value) return
    direction.value = defaultDirection()
    if (previewMode.value) { material.value = { name: '20黑色-钠电', unit: '公斤' }; return }
    if (selectionMode.value) {
      const result = await http.get<EntryMaterial[], EntryMaterial[]>('/material-entry/materials', { skipErrorToast: true })
      if (sequence === loadSequence) entryMaterials.value = result
      return
    }
    const result = await http.get<any, any>(entryApiPath.value, { skipErrorToast: true })
    if (sequence !== loadSequence) return
    material.value = result
    try {
      const saved = JSON.parse(sessionStorage.getItem(storageKey()) || 'null')
      if (saved?.requestId && ['IN', 'OUT'].includes(saved.direction) && typeof saved.qty === 'string') { pending.value = saved; direction.value = saved.direction; qty.value = saved.qty }
    } catch { /* Storage may be unavailable in private browsers. */ }
  } catch (ex) { if (sequence === loadSequence && user.isLoggedIn) loadError.value = message(ex) }
  finally { if (sequence === loadSequence) loading.value = false }
}
async function submit() {
  if (saving.value || receipt.value || !canRegister.value) return
  validation.value = ''; error.value = ''
  if (!pending.value) {
    if (!direction.value) { validation.value = '请选择出库或入库'; return }
    if (!directionAllowed(direction.value)) { validation.value = '当前账号没有该登记类型的权限'; return }
    const value = qty.value.trim()
    if (!/^\d{1,15}(\.\d{1,3})?$/.test(value) || Number(value) <= 0) { validation.value = '请输入大于0的数量，最多三位小数'; return }
    if (previewMode.value) {
      receipt.value = { id: 0, direction: direction.value, qty: Number(value), unit: material.value!.unit, businessDate: '', operator: user.user?.realName || user.user?.username, operatorUsername: user.user?.username }
      return
    }
    pending.value = { requestId: requestId(), direction: direction.value, qty: value }
    try { sessionStorage.setItem(storageKey(), JSON.stringify(pending.value)) } catch { /* In-memory retry remains available. */ }
  }
  saving.value = true
  try {
    await user.loadProfile()
    if (!canRegister.value) return
    receipt.value = await http.post<any, Receipt>(`${entryApiPath.value}/entries`, pending.value, { skipErrorToast: true })
    pending.value = undefined
    try { sessionStorage.removeItem(storageKey()) } catch { /* Receipt is already confirmed. */ }
  } catch (ex: any) {
    error.value = message(ex)
    if (ex?.response?.status === 403) await user.loadProfile().catch(() => {})
    // Business validation is a definitive rejection; network errors retain the same request for safe retry.
    if (ex?.response?.status === 400) {
      pending.value = undefined
      try { sessionStorage.removeItem(storageKey()) } catch { /* Ignore unavailable storage. */ }
    }
  } finally { saving.value = false }
}
function nextEntry() { receipt.value = undefined; qty.value = ''; direction.value = defaultDirection(); error.value = ''; validation.value = '' }
watch([() => route.fullPath, () => user.token], load)
onMounted(load)
</script>

<style scoped>
.scan-page { height:100vh; height:100dvh; overflow-y:auto; overflow-x:hidden; -webkit-overflow-scrolling:touch; background:#f2f6fb; padding:28px 18px; box-sizing:border-box; }
.scan-shell { max-width:460px; margin:0 auto; }
.selection-shell { max-width:1160px; }
.selection-heading { display:flex; justify-content:space-between; align-items:center; gap:20px; }
.selection-heading p { color:#71839a; font-size:14px; margin:10px 0 0; }
.selection-count { color:#8996a8; white-space:nowrap; font-size:13px; }
.material-search { margin:24px 0; max-width:420px; }
.material-grid { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:18px; }
.material-bubble { border:1px solid #d7e6fa; border-radius:28px; min-height:132px; padding:16px 12px; display:flex; flex-direction:column; justify-content:center; align-items:center; gap:8px; background:#f6faff; color:#284c78; cursor:pointer; transition:background .15s,border-color .15s,box-shadow .15s; }
.material-bubble:hover:not(:disabled) { background:#eaf3ff; border-color:#6da0e8; box-shadow:0 6px 18px #3978ed18; }
.material-bubble:focus-visible { outline:3px solid #7eaff5; outline-offset:3px; }
.material-bubble strong { font-size:16px; line-height:1.5; overflow-wrap:anywhere; }
.bubble-category { color:#7a95b7; font-size:12px; }
.bubble-unit { color:#6484aa; font-size:12px; }
.material-bubble:disabled { background:#f6f7f9; border-color:#e8ebf0; color:#9aa6b6; cursor:not-allowed; }
.material-bubble:disabled .bubble-category,.material-bubble:disabled .bubble-unit { color:#a3afbd; }
.back-to-materials { margin:0 0 20px; }
@media(max-width:700px) { .material-grid { grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; } .selection-card { padding:24px 18px; } .material-bubble { min-height:116px; padding:14px 10px; border-radius:24px; } }
@media(max-width:480px) { .material-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
.scan-brand { color:#526a84; font-size:14px; font-weight:600; margin:0 0 24px 4px; }
.employee-bar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin:-10px 4px 16px; color:#526a84; font-size:13px; }
.employee-bar small { display:block; color:#8996a8; margin-top:4px; }
.login-description { color:#71839a; font-size:14px; line-height:1.8; margin:16px 0 26px; }
.preview-badge { margin-left:10px; padding:3px 8px; color:#416d9c; background:#e5eef9; border-radius:5px; font-size:12px; }
.scan-card { background:#fff; padding:30px 24px 24px; border:1px solid #e5ebf3; border-radius:20px; box-shadow:0 12px 40px #1c3b6410; }
.scan-kicker { font-size:13px; color:#71839a; margin-bottom:10px; }
h1 { margin:0; font-size:28px; line-height:1.4; color:#1d3552; overflow-wrap:anywhere; }
.scan-unit { color:#7b8ba0; font-size:14px; margin:14px 0 30px; }
.scan-unit strong { color:#304d70; background:#eef4fc; padding:5px 12px; margin-left:8px; border-radius:6px; }
.direction-options { display:flex; gap:12px; width:100%; }
.direction-options button { flex:1; border:1px solid #dce4ef; border-radius:10px; background:#fff; height:58px; font-size:18px; color:#536980; cursor:pointer; }
.direction-options button.selected { color:#2464d8; border:2px solid #3978ed; background:#eff5ff; font-weight:600; }
.direction-options button:disabled { cursor:default; }
.direction-hint { color:#71839a; font-size:12px; margin:8px 0 0; }
.submit { width:100%; height:52px; font-size:18px; border-radius:10px; margin-top:8px; }
.scan-hint { color:#8996a8; font-size:12px; line-height:1.7; margin:16px 0 0; text-align:center; }
.error { color:#be403c; line-height:1.6; }
.receipt { text-align:center; padding:0 0 24px; color:#567086; }
.receipt h2 { color:#1b755e; font-size:24px; }
.receipt-check { margin:0 auto; border-radius:50%; background:#eaf8f1; color:#259d73; width:64px; height:64px; line-height:64px; font-size:34px; }
:deep(.el-form-item__label) { font-weight:600; color:#354e6c; padding-bottom:10px; }
:deep(.el-input__wrapper) { min-height:50px; font-size:22px; }
:deep(.el-form-item) { margin-bottom:26px; }
@media(max-width:360px) { .scan-page { padding:18px 12px; } .scan-card { padding:24px 18px; } }
</style>
