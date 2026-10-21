<template>
  <div class="users-page">
    <div class="page-title-bar">
      <div><h2>用户管理</h2><p>设置登录账户及可使用的功能，勾选的菜单即可查看和操作。</p></div>
      <el-button type="primary" @click="openEditor()">新增用户</el-button>
    </div>
    <el-table v-loading="loading" :data="pagedRows" border>
      <el-table-column label="姓名" width="90"><template #default="{ row }">{{ row.realName || '未设置' }}</template></el-table-column>
      <el-table-column prop="username" label="登录账户" width="120" show-overflow-tooltip />
      <el-table-column label="权限" min-width="150">
        <template #default="{ row }">
          <div class="permission-summary">
            <el-tag v-if="row.permissionMode === 'ALL_BUSINESS'" type="success">全部业务权限 · 包含未来新增</el-tag>
            <template v-else><el-tag v-for="entry in visibleBusiness(row)" :key="entry.code" type="primary">{{ entry.label }}</el-tag></template>
            <el-tag v-if="row.effectiveMenuCodes.includes('orders') && !row.orderPricesVisible" type="info">订单价格不可见 · 只读</el-tag>
            <el-tag v-if="row.effectiveMenuCodes.includes('users')">用户管理</el-tag>
            <span v-if="!row.effectiveMenuCodes.length" class="muted">暂无功能权限</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80" class-name="status-column"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.owner && row.id !== user.user?.id" @click="openEditor(row)">编辑</el-button>
          <el-button link type="danger" :disabled="row.owner || row.id === user.user?.id" @click="deleteUser(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="total" layout="total, prev, pager, next, jumper" /><span class="muted">每页 {{ pageSize }} 条</span></div>
    <el-dialog v-model="editorVisible" :title="editingId ? '编辑用户' : '新增用户'" width="660px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="95px">
        <el-form-item label="姓名" prop="realName"><el-input v-model="form.realName" maxlength="100" placeholder="用于登录后显示及出入库操作人记录的员工姓名" /></el-form-item>
        <el-form-item label="登录账户" prop="username"><el-input v-model="form.username" maxlength="64" placeholder="用于登录的账户，不能重复" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" :placeholder="editingId ? '留空保留原密码，填写则重设密码' : '请设置至少6位密码'" /></el-form-item>
        <el-form-item label="启用账号"><el-switch v-model="form.enabled" :disabled="editingOwner" /><span class="inline-note">{{ editingOwner ? '老板账号始终启用' : '停用后不能登录' }}</span></el-form-item>
        <el-form-item label="权限管理">
          <div class="permissions-box">
            <el-checkbox :model-value="form.permissionMode === 'ALL_BUSINESS'" :disabled="editingOwner" @change="toggleAllBusiness">全部业务权限（包含未来新增菜单）</el-checkbox>
            <p class="permission-note">{{ form.permissionMode === 'ALL_BUSINESS' ? '业务菜单全部开放，用户管理单独授权。' : '按侧边栏勾选；新增菜单默认不开放。' }}</p>
            <el-tree ref="treeRef" :data="permissionTree" node-key="code" show-checkbox default-expand-all :props="{ label: 'label', children: 'children', disabled: 'disabled' }" @check="treeChecked">
              <template #default="{ data }">
                <span class="permission-node">
                  <span>{{ data.label }}</span>
                  <el-checkbox v-if="data.code === 'orders'" v-model="form.orderPricesVisible"
                    class="price-permission" :disabled="editingOwner || !ordersEnabled" @click.stop @keydown.stop>价格可见</el-checkbox>
                </span>
              </template>
            </el-tree>
            <p class="permission-note">在“库存操作 → 原料出入库登记”下分别勾选“出库登记”和“入库登记”，可只允许其中一种操作，无需开放原料库存或其他业务功能。</p>
            <p class="permission-note price-note">取消“价格可见”后，销售订单仅可查看，不能新增、编辑或删除，导出为无价订单。</p>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="editorVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveUser">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useListPagination } from '@/composables/useListPagination'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import http from '@/api/http'
import type { MenuEntry, UserProfile } from '@/permission/menus'

const user = useUserStore()
const router = useRouter()
const rows = ref<UserProfile[]>([])
const loading = ref(false)
const saving = ref(false)
const editorVisible = ref(false)
const editingId = ref<number | null>(null)
const editingOwner = ref(false)
const formRef = ref<FormInstance>()
const treeRef = ref<any>()
const form = reactive({ realName: '', username: '', password: '', enabled: true, permissionMode: 'CUSTOM', orderPricesVisible: true, menuCodes: [] as string[] })
const rules: FormRules = {
  realName: [{ required: true, whitespace: true, message: '请填写姓名', trigger: 'blur' }],
  username: [{ required: true, whitespace: true, message: '请填写登录账户', trigger: 'blur' }],
  password: [{ validator: (_rule, value, callback) => {
    if (!editingId.value && !value) callback(new Error('新增用户必须填写密码'))
    else if (value && (value.length < 6 || new TextEncoder().encode(value).length > 72)) callback(new Error('密码至少6位，不能超过72字节'))
    else callback()
  }, trigger: 'blur' }]
}
interface TreeNode { code: string; label: string; disabled?: boolean; children?: TreeNode[] }
const ordersEnabled = computed(() => form.permissionMode === 'ALL_BUSINESS' || form.menuCodes.includes('orders'))
const permissionTree = computed(() => {
  const result: TreeNode[] = []
  for (const entry of user.menuCatalog.filter(item => !item.parentCode)) {
    const children = user.menuCatalog.filter(item => item.parentCode === entry.code)
    const disabled = editingOwner.value || (entry.business && form.permissionMode === 'ALL_BUSINESS')
    const leaf: TreeNode = children.length
      ? { code: 'group:' + entry.code, label: entry.label, disabled, children: children.map(item => ({ code: item.code, label: item.label, disabled })) }
      : { code: entry.code, label: entry.label, disabled }
    if (!entry.group) result.push(leaf)
    else {
      let group = result.find(item => item.code === 'group:' + entry.group)
      if (!group) { group = { code: 'group:' + entry.group, label: entry.group, children: [], disabled: editingOwner.value || form.permissionMode === 'ALL_BUSINESS' }; result.push(group) }
      group.children!.push(leaf)
    }
  }
  return result
})
function visibleBusiness(row: UserProfile) { return user.menuCatalog.filter(entry => entry.business && !user.menuCatalog.some(item => item.parentCode === entry.code) && row.effectiveMenuCodes.includes(entry.code)) }
function checkedCodes() { return (treeRef.value?.getCheckedKeys() || []).filter((code: string) => !code.startsWith('group:')) as string[] }
function treeChecked() { form.menuCodes = checkedCodes() }
async function syncTree() {
  await nextTick()
  const codes = form.permissionMode === 'ALL_BUSINESS' ? user.menuCatalog.filter(entry => (entry.business || form.menuCodes.includes(entry.code)) && !user.menuCatalog.some(item => item.parentCode === entry.code)).map(entry => entry.code) : form.menuCodes
  treeRef.value?.setCheckedKeys(codes)
}
async function toggleAllBusiness(value: boolean | string | number) {
  form.menuCodes = checkedCodes()
  form.permissionMode = value ? 'ALL_BUSINESS' : 'CUSTOM'
  await syncTree()
}
async function load() {
  loading.value = true
  try { rows.value = await http.get<UserProfile[], UserProfile[]>('/system/users') }
  finally { loading.value = false }
}
async function openEditor(row?: UserProfile) {
  editingId.value = row?.id || null
  editingOwner.value = !!row?.owner
  Object.assign(form, { realName: row?.realName || '', username: row?.username || '', password: '', enabled: row?.enabled ?? true, permissionMode: row?.permissionMode || 'CUSTOM', orderPricesVisible: row?.owner ? true : row?.orderPricesVisible ?? true, menuCodes: [...(row?.menuCodes || [])] })
  if (editingOwner.value && !form.menuCodes.includes('users')) form.menuCodes.push('users')
  editorVisible.value = true
  await syncTree()
  formRef.value?.clearValidate()
}
async function saveUser() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    const checked = checkedCodes()
    const payload = { ...form, username: form.username.trim(), realName: form.realName.trim(), menuCodes: form.permissionMode === 'ALL_BUSINESS' ? checked.filter(code => !user.menuCatalog.find(entry => entry.code === code)?.business) : checked }
    const self = editingId.value === user.user?.id
    const credentialsChanged = self && (payload.username !== user.user?.username || !!payload.password)
    if (editingId.value) await http.put('/system/users/' + editingId.value, payload)
    else await http.post('/system/users', payload)
    editorVisible.value = false
    ElMessage.success(credentialsChanged ? '已保存，请使用更新后的账户和密码重新登录' : '用户已保存')
    if (credentialsChanged) { user.clearSession(); await router.replace('/login'); return }
    await user.loadProfile()
    if (!user.canAccess('/users')) { await router.replace(user.firstPath); return }
    await load()
  } finally { saving.value = false }
}
async function deleteUser(row: UserProfile) {
  try { await ElMessageBox.confirm(`删除 ${row.realName || row.username} 后，该账号将无法登录。`, '删除用户', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) }
  catch { return }
  await http.delete('/system/users/' + row.id)
  ElMessage.success('用户已删除')
  await load()
}
const { currentPage, pageSize, total, pagedRows, rowIndex } = useListPagination(rows, [])
onMounted(load)
</script>

<style scoped>
.users-page { background: #fff; padding: 24px; border: 1px solid var(--oms-border); border-radius: 12px; }
.page-title-bar { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 24px; }
h2 { margin: 0 0 8px; font-size: 22px; color: var(--oms-heading); }
p { margin: 0; color: var(--oms-muted); font-size: 13px; }
.permission-summary { display: flex; flex-wrap: wrap; gap: 6px; padding: 6px 0; }
.permission-summary .el-tag { max-width: 100%; height: auto; min-height: 24px; line-height: 1.5; padding: 4px 8px; }
.permission-summary :deep(.el-tag__content) { white-space: normal; overflow-wrap: anywhere; }
.users-page :deep(.status-column .cell) { padding-left: 6px; padding-right: 6px; }
.permissions-box { width: 100%; padding: 12px 16px; border: 1px solid var(--oms-border); border-radius: 8px; }
.permission-note { margin: 4px 0 10px; line-height: 1.7; }
.inline-note { margin-left: 12px; font-size: 12px; color: var(--oms-muted); }
.permission-node { display: inline-flex; align-items: center; gap: 22px; }
.price-permission { margin: 0; height: 26px; }
.price-note { margin-top: 10px; font-size: 12px; }
.muted { color: var(--oms-muted); }
</style>
