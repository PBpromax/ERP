import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { h } from 'vue'

export const menuRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'no-access', component: { render: () => h('div', { class: 'empty-permissions' }, '暂无功能权限，请联系管理员分配权限。') }, meta: { title: '暂无功能权限' } },
      { path: 'dashboard', component: () => import('@/views/dashboard/Index.vue'), meta: { title: '首页驾驶舱' } },
      { path: 'customers', component: () => import('@/views/master/Customers.vue'), meta: { title: '客户档案' } },
      { path: 'products', component: () => import('@/views/master/Products.vue'), meta: { title: '产品资料' } },
      { path: 'materials', component: () => import('@/views/master/Materials.vue'), meta: { title: '原料库存' } },
      { path: 'suppliers', component: () => import('@/views/master/Suppliers.vue'), meta: { title: '供应商' } },
      { path: 'quotations', component: () => import('@/views/sales/Quotations.vue'), meta: { title: '报价管理' } },
      { path: 'orders', component: () => import('@/views/sales/Orders.vue'), meta: { title: '销售订单' } },
      { path: 'deliveries', component: () => import('@/views/sales/Deliveries.vue'), meta: { title: '发货管理' } },
      { path: 'product-stocks', component: () => import('@/views/inventory/ProductStocks.vue'), meta: { title: '成品库存' } },
      { path: 'product-movements', component: () => import('@/views/inventory/ProductMovements.vue'), meta: { title: '出入库管理' } },
      { path: 'inventory-transactions', component: () => import('@/views/inventory/Transactions.vue'), meta: { title: '库存流水' } },
      { path: 'semi-finished-stocks', component: () => import('@/views/production/SemiFinishedStocks.vue'), meta: { title: '生产库存' } },
      { path: 'production-plans', component: () => import('@/views/production/Plans.vue'), meta: { title: '生产计划' } },
      { path: 'production-records', component: () => import('@/views/production/ProductionRecords.vue'), meta: { title: '工人管理' } },
      { path: 'statements', component: () => import('@/views/finance/Statements.vue'), meta: { title: '对账单' } },
      { path: 'invoices', component: () => import('@/views/finance/Invoices.vue'), meta: { title: '对账已开票' } },
      { path: 'receipts', component: () => import('@/views/finance/Receipts.vue'), meta: { title: '收款登记' } },
      { path: 'customer-summaries', redirect: '/receipts' },
      { path: 'quality-reports', component: () => import('@/views/quality/Reports.vue'), meta: { title: '质检报告' } },
      { path: 'notifications', component: () => import('@/views/notify/Notifications.vue'), meta: { title: '消息提醒' } },
      { path: 'users', component: () => import('@/views/system/Users.vue'), meta: { title: '用户管理' } },
      { path: 'roles', component: () => import('@/views/system/Roles.vue'), meta: { title: '角色管理' } },
      { path: 'field-permissions', component: () => import('@/views/system/FieldPermissions.vue'), meta: { title: '字段权限' } },
      { path: 'audit-logs', component: () => import('@/views/system/AuditLogs.vue'), meta: { title: '操作日志' } }
    ]
  },
  { path: '/login', component: () => import('@/views/Login.vue'), meta: { title: '登录' } },
  { path: '/worker/material-entry/:materialId', component: () => import('@/views/inventory/MaterialScanEntry.vue'), meta: { title: '原料出入库登记', public: true, materialEntry: true } },
  { path: '/worker/material-entry', component: () => import('@/views/inventory/MaterialScanEntry.vue'), meta: { title: '原料出入库登记', public: true, materialEntry: true } },
  { path: '/worker/material-preview', component: () => import('@/views/inventory/MaterialScanEntry.vue'), meta: { title: '原料出入库登记演示', public: true, preview: true, materialEntry: true } },
  { path: '/worker/material/:token', component: () => import('@/views/inventory/MaterialScanEntry.vue'), meta: { title: '原料出入库登记', public: true, materialEntry: true } },
  { path: '/worker/production/:token', component: () => import('@/views/production/WorkerProductionEntry.vue'), meta: { title: '生产录入', public: true } },
  { path: '/print/:type/:id', component: () => import('@/views/print/DocumentPrint.vue'), meta: { title: '打印' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes: menuRoutes
})

router.beforeEach(async (to) => {
  const user = useUserStore()
  if (to.meta.public) return true
  if (!user.isLoggedIn) return to.path === '/login' ? true : '/login'
  try { await user.loadProfile() }
  catch { return user.isLoggedIn ? false : '/login' }
  if (!user.isLoggedIn) return '/login'
  if (to.path === '/login') return user.firstPath
  if (to.path === '/no-access' && user.menuCodes.length) return user.firstPath
  if (!user.canAccess(to.path)) return user.firstPath
  return true
})

export default router
