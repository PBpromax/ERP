<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { invalidateReferenceData } from '@/api/referenceData'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
const user = useUserStore()
const router = useRouter()
function expired() { user.clearSession(); if (!router.currentRoute.value.meta.materialEntry) void router.replace('/login') }
async function storageChanged(event: StorageEvent) {
  if (event.key !== 'OMS_TOKEN') return
  user.$patch({ token: event.newValue || '', user: null, menuCatalog: [], fieldPermissions: {} })
  invalidateReferenceData()
  if (!event.newValue) { if (!router.currentRoute.value.meta.materialEntry) void router.replace('/login') }
  else {
    try { await user.loadProfile(); if (!router.currentRoute.value.meta.materialEntry) void router.replace(user.firstPath) } catch { /* HTTP interceptor handles expired sessions. */ }
  }
}
onMounted(() => { window.addEventListener('oms:auth-expired', expired); window.addEventListener('storage', storageChanged) })
onUnmounted(() => { window.removeEventListener('oms:auth-expired', expired); window.removeEventListener('storage', storageChanged) })
</script>
