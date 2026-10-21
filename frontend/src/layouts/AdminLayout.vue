<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <div class="brand-logo" style="background: #fff; overflow: hidden; flex-shrink: 0"><img src="/company-logo.jpg" alt="荣之巨公司 Logo" style="width: 100%; height: 100%; object-fit: contain; transform: scale(1.35)" /></div>
        <div>
          <div class="brand-title" style="font-size: 14px; line-height: 1.5; max-width: 142px; white-space: normal">荣之巨业务管理系统</div>
          <div class="brand-sub">Operations Suite</div>
        </div>
      </div>
      <el-scrollbar class="aside-scroll">
        <el-menu router :default-active="$route.path" class="menu">
          <template v-for="item in navigation" :key="item.key">
            <el-sub-menu v-if="item.children.length" :index="item.key">
              <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></template>
              <el-menu-item v-for="child in item.children" :key="child.code" :index="child.path">{{ child.label }}</el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>
    <el-container class="content-shell">
      <el-header class="header">
        <div class="header-left">
          <el-popover placement="bottom-start" width="360" trigger="click" popper-class="top-popover">
            <template #reference>
              <el-button class="icon-button" text>
                <el-icon><Grid /></el-icon>
              </el-button>
            </template>
            <div class="popover-title">快捷导航</div>
            <div class="quick-grid">
              <button v-for="item in quickLinks" :key="item.path" class="quick-item" @click="go(item.path)">
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.label }}</span>
              </button>
            </div>
          </el-popover>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ currentGroup }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ route.meta.title || '首页' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div v-if="quickLinks.length" class="header-search">
          <el-autocomplete
            v-model="globalKeyword"
            :fetch-suggestions="fetchGlobalSuggestions"
            placeholder="搜索订单号、客户公司、产品编号"
            value-key="value"
            clearable
            style="width: 100%"
            @select="selectGlobalResult"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
            <template #default="{ item }">
              <div class="search-result">
                <el-tag size="small" effect="plain">{{ item.type }}</el-tag>
                <div class="search-result-main">
                  <div class="search-title">{{ item.title }}</div>
                  <div v-if="item.subtitle" class="search-sub">{{ item.subtitle }}</div>
                </div>
              </div>
            </template>
          </el-autocomplete>
        </div>
        <div class="user">
          <el-popover placement="bottom-end" width="420" trigger="click" popper-class="top-popover">
            <template #reference>
              <el-button class="icon-button" text>
                <el-icon><QuestionFilled /></el-icon>
              </el-button>
            </template>
            <div class="popover-title">流程帮助</div>
            <div class="flow-list">
              <div v-for="(step, index) in flowSteps" :key="step.title" class="flow-step">
                <div class="flow-dot">{{ index + 1 }}</div>
                <div>
                  <div class="flow-title">{{ step.title }}</div>
                  <div class="flow-desc">{{ step.desc }}</div>
                </div>
              </div>
            </div>
          </el-popover>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-menu">
              <el-avatar class="user-avatar" :size="28"><el-icon><UserFilled /></el-icon></el-avatar>
              <span class="user-name">{{ user.user?.realName }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ user.user?.username || '当前账号' }}</el-dropdown-item>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
    <el-dialog v-model="passwordVisible" title="修改密码" width="420px">
      <el-form :model="passwordForm" label-width="90px">
        <el-form-item label="原密码"><el-input v-model="passwordForm.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="passwordForm.newPassword" type="password" show-password /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="passwordForm.confirmPassword" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="changePassword">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted, onUnmounted } from 'vue'
import type { MenuEntry } from '@/permission/menus'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import {
  Box,
  DocumentChecked,
  FolderOpened,
  Grid,
  House,
  QuestionFilled,
  Search,
  ShoppingCart,
  UserFilled,
  Wallet
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import http from '@/api/http'


const route = useRoute()
const router = useRouter()
const user = useUserStore()
const globalKeyword = ref('')
const passwordVisible = ref(false)
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const passwordSaving = ref(false)
const navigation = computed(() => {
  const items: { key: string; label: string; path: string; icon: any; children: MenuEntry[] }[] = []
  const icons: Record<string, any> = { '基础资料': FolderOpened, '销售管理': ShoppingCart, '财务管理': Wallet }
  for (const entry of user.menuCatalog.filter(entry => !entry.parentCode && user.hasMenu(entry.code))) {
    if (!entry.group) items.push({ key: entry.code, label: entry.label, path: entry.path, icon: entry.code === 'dashboard' ? House : UserFilled, children: [] })
    else {
      let group = items.find(item => item.key === entry.group)
      if (!group) { group = { key: entry.group, label: entry.group, path: '', icon: icons[entry.group] || FolderOpened, children: [] }; items.push(group) }
      group.children.push(entry)
    }
  }
  return items
})

const groupMap: Record<string, string> = {
  '/dashboard': '首页',
  '/customers': '基础资料',
  '/products': '基础资料',
  '/materials': '基础资料',
  '/suppliers': '基础资料',
  '/quotations': '销售管理',
  '/orders': '销售管理',
  '/deliveries': '销售管理',
  '/product-stocks': '库存管理',
  '/product-movements': '库存管理',
  '/inventory-transactions': '库存管理',
  '/semi-finished-stocks': '生产管理',
  '/production-plans': '生产管理',
  '/production-records': '生产管理',
  '/statements': '财务管理',
  '/invoices': '财务管理',
  '/receipts': '财务管理',
  '/quality-reports': '质检报告',
  '/notifications': '消息提醒',
  '/users': '系统管理',
  '/roles': '系统管理',
  '/field-permissions': '系统管理',
  '/audit-logs': '系统管理'
}

const currentGroup = computed(() => user.menuCatalog.find(item => item.path === route.path)?.group || groupMap[route.path] || '工作台')
const allQuickLinks = [
  { label: '报价管理', path: '/quotations', icon: ShoppingCart },
  { label: '销售订单', path: '/orders', icon: ShoppingCart },
  { label: '发货管理', path: '/deliveries', icon: DocumentChecked },
  { label: '产品资料', path: '/products', icon: Box },
  { label: '客户档案', path: '/customers', icon: FolderOpened }
]
const quickLinks = computed(() => allQuickLinks.filter(item => user.canAccess(item.path)))
const flowSteps = [
  { title: '报价管理', desc: '录入客户与产品，生成报价单。' },
  { title: '销售订单', desc: '报价转单或手动录入订单。' },
  { title: '发货与质检', desc: '登记发货量并上传质检报告。' },
  { title: '对账单', desc: '按客户和月份勾选送货记录生成。' },
  { title: '开票与收款', desc: '对账单开票后同步发货状态，再登记回款。' }
]

async function logout() {
  try { await user.logout() } finally { await router.replace('/login') }
}

function go(path: string) {
  router.push(path)
}

async function fetchGlobalSuggestions(query: string, cb: (items: any[]) => void) {
  const key = query.trim()
  if (!key) {
    cb([])
    return
  }
  try {
    const rows = await http.get<any[], any[]>(`/search?keyword=${encodeURIComponent(key)}`)
    cb(rows)
  } catch (error) {
    cb([])
  }
}

function selectGlobalResult(item: any) {
  globalKeyword.value = ''
  if (item.path) router.push(item.path)
}

function handleUserCommand(command: string) {
  if (command === 'password') {
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordVisible.value = true
  }
  if (command === 'logout') logout()
}

async function changePassword() {
  if (!passwordForm.oldPassword) return ElMessage.warning('请填写原密码')
  if (passwordForm.newPassword.length < 6) return ElMessage.warning('新密码至少6位')
  if (passwordForm.newPassword !== passwordForm.confirmPassword) return ElMessage.warning('两次新密码不一致')
  passwordSaving.value = true
  try {
    await http.post('/auth/change-password', passwordForm)
    ElMessage.success('密码已修改，请重新登录')
    passwordVisible.value = false
    user.clearSession()
    await router.replace('/login')
  } finally { passwordSaving.value = false }
}
let refreshTimer: ReturnType<typeof setInterval>
let refreshing = false
async function refreshPermissions() {
  if (refreshing || !user.isLoggedIn) return
  refreshing = true
  try {
    await user.loadProfile()
    if (!user.isLoggedIn) await router.replace('/login')
    else if (!user.canAccess(route.path) || (route.path === '/no-access' && user.menuCodes.length)) await router.replace(user.firstPath)
  } catch { /* Keep existing view on a temporary connection error. */ }
  finally { refreshing = false }
}
onMounted(() => {
  refreshTimer = setInterval(refreshPermissions, 30_000)
  window.addEventListener('focus', refreshPermissions)
  window.addEventListener('oms:permissions-refresh', refreshPermissions)
})
onUnmounted(() => {
  clearInterval(refreshTimer)
  window.removeEventListener('focus', refreshPermissions)
  window.removeEventListener('oms:permissions-refresh', refreshPermissions)
})

</script>

<style scoped>
.layout {
  height: 100vh;
  overflow: hidden;
  background: var(--oms-bg);
}

.aside {
  background: #fff;
  border-right: 1px solid var(--oms-border);
  color: var(--oms-text);
  height: 100vh;
  overflow: hidden;
}

.brand {
  height: 68px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--oms-border);
}

.brand-logo {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: var(--oms-primary);
  color: white;
  font-size: 16px;
  font-weight: 800;
}

.brand-title {
  color: var(--oms-heading);
  font-size: 15px;
  font-weight: 760;
}

.brand-sub {
  color: var(--oms-muted);
  font-size: 11px;
  margin-top: 2px;
}

.aside-scroll {
  height: calc(100vh - 68px);
  padding: 10px 0;
}

.menu {
  border-right: none;
  background: transparent;
}

:deep(.el-menu) {
  border-right: none;
  background: #fff;
}

:deep(.el-sub-menu .el-menu) {
  background: #fff;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  color: #475467;
  background: transparent;
  width: auto;
  height: 38px;
  line-height: 38px;
  margin: 2px 10px;
  border-radius: 6px;
  padding-left: 14px !important;
  padding-right: 12px !important;
  font-size: 13px;
  font-weight: 600;
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover) {
  color: var(--oms-heading);
  background: #f7f9fc;
}

:deep(.el-sub-menu.is-opened > .el-sub-menu__title) {
  color: var(--oms-heading);
  background: #fff;
}

:deep(.el-sub-menu .el-menu-item) {
  color: #667085;
  background: #fff;
  width: auto;
  height: 36px;
  line-height: 36px;
  margin: 2px 10px 2px 28px;
  border-radius: 6px;
  padding-left: 14px !important;
  padding-right: 10px !important;
  min-width: 0;
  font-size: 13px;
}

:deep(.el-menu-item.is-active) {
  background: var(--oms-primary-soft);
  color: var(--oms-primary);
}

:deep(.el-sub-menu .el-menu-item.is-active) {
  background: var(--oms-primary-soft);
  color: var(--oms-primary);
}

.content-shell {
  height: 100vh;
  min-width: 0;
  overflow: hidden;
}

.header {
  height: 64px;
  flex: 0 0 64px;
  background: rgba(255, 255, 255, .94);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--oms-border);
  display: grid;
  grid-template-columns: minmax(190px, 1fr) minmax(240px, 440px) minmax(190px, 1fr);
  align-items: center;
  padding: 0 18px;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.header-search {
  min-width: 0;
}

.header-search :deep(.el-input__wrapper) {
  border-radius: 8px;
}

.icon-button {
  color: #667085;
  height: 34px;
  width: 34px;
}

.icon-button:hover {
  background: #f6f8fb;
  color: var(--oms-primary);
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #475467;
  font-size: 13px;
  justify-content: flex-end;
  min-width: 0;
}

.user :deep(.el-badge) {
  display: inline-flex;
}

.user-menu {
  align-items: center;
  cursor: pointer;
  display: inline-flex;
  gap: 8px;
  min-width: 0;
  outline: none;
}

.user-avatar {
  border-radius: 50%;
  flex: 0 0 28px;
  height: 28px;
  min-width: 28px;
  overflow: hidden;
  width: 28px;
}

.user-avatar :deep(.el-icon) {
  font-size: 16px;
}

.user-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-tag {
  max-width: 76px;
  overflow: hidden;
}

.popover-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.popover-title {
  color: var(--oms-heading);
  font-size: 14px;
  font-weight: 760;
}

.quick-grid {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quick-item {
  align-items: center;
  background: #f8fbff;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  color: #475467;
  cursor: pointer;
  display: flex;
  gap: 8px;
  height: 42px;
  padding: 0 12px;
  text-align: left;
}

.quick-item:hover {
  background: var(--oms-primary-soft);
  border-color: var(--oms-primary-border);
  color: var(--oms-primary);
}

.search-result {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
  padding: 3px 0;
}

.search-result-main {
  min-width: 0;
}

.search-title {
  color: var(--oms-heading);
  font-size: 13px;
  font-weight: 700;
  line-height: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-sub {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notify-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 340px;
  overflow: auto;
}

.notify-item {
  background: #f8fbff;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  cursor: pointer;
  padding: 10px 12px;
}

.notify-item:hover {
  border-color: var(--oms-primary-border);
}

.notify-title {
  color: var(--oms-heading);
  font-size: 13px;
  font-weight: 760;
  line-height: 20px;
}

.notify-content {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
  margin-top: 2px;
}

.flow-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 12px;
}

.flow-step {
  display: grid;
  gap: 10px;
  grid-template-columns: 28px minmax(0, 1fr);
}

.flow-dot {
  align-items: center;
  background: var(--oms-primary-soft);
  border: 1px solid var(--oms-primary-border);
  border-radius: 50%;
  color: var(--oms-primary);
  display: inline-flex;
  font-size: 12px;
  font-weight: 800;
  height: 28px;
  justify-content: center;
  width: 28px;
}

.flow-title {
  color: var(--oms-heading);
  font-size: 13px;
  font-weight: 760;
  line-height: 20px;
}

.flow-desc {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
}

@media (max-width: 1180px) {
  .header {
    grid-template-columns: minmax(170px, 1fr) minmax(220px, 360px) auto;
  }

  .role-tag {
    display: none;
  }
}

@media (max-width: 980px) {
  .header-search {
    display: none;
  }

  .header {
    grid-template-columns: minmax(170px, 1fr) auto;
  }
}

@media (max-width: 760px) {
  .aside {
    display: none;
  }

  .content-shell {
    width: 100%;
  }

  .header {
    grid-template-columns: 34px minmax(0, 1fr);
    padding: 0 12px;
  }

  .header-left {
    gap: 0;
  }

  .header-left :deep(.el-breadcrumb) {
    display: none;
  }

  .user {
    gap: 4px;
  }

  .user > :deep(.el-badge),
  .user > .icon-button,
  .user-name,
  .role-tag {
    display: none;
  }
}

.main {
  height: calc(100vh - 64px);
  overflow: auto;
  background: var(--oms-bg);
}
</style>
