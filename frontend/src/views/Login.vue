<template>
  <div class="login">
    <div class="login-card">
      <div class="logo" style="background: #fff; overflow: hidden"><img src="/company-logo.jpg" alt="荣之巨公司 Logo" style="width: 100%; height: 100%; object-fit: contain; transform: scale(1.35)" /></div>
      <h1 class="login-title">荣之巨业务管理系统</h1>
      <p class="login-sub">统一报价、订单、库存、生产、财务与质检审计。</p>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input ref="usernameInput" v-model="form.username" placeholder="登录账户" size="large" autocomplete="username" />
        </el-form-item>
        <el-form-item>
          <el-input ref="passwordInput" v-model="form.password" placeholder="密码" size="large" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" size="large" class="login-btn" native-type="submit" :loading="loading">登录系统</el-button>
      </el-form>
      <div class="login-footer">© 荣之巨 · 仅限授权用户访问</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type InputInstance } from 'element-plus'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const user = useUserStore()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const usernameInput = ref<InputInstance>()
const passwordInput = ref<InputInstance>()

async function submit() {
  if (loading.value) return
  if (!form.username.trim()) {
    ElMessage({ type: 'error', message: '请输入登录账户', grouping: true })
    usernameInput.value?.focus()
    return
  }
  if (!form.password.trim()) {
    ElMessage({ type: 'error', message: '请输入密码', grouping: true })
    passwordInput.value?.focus()
    return
  }
  loading.value = true
  try {
    await user.login(form.username.trim(), form.password)
    await router.replace(user.firstPath)
  } catch {
    // API failures already display their human-readable message through the HTTP interceptor.
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--oms-bg);
  padding: 24px 16px;
}

.login-card {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border: 1px solid var(--oms-border);
  box-shadow: var(--oms-shadow);
  border-radius: 14px;
  padding: 40px 36px 32px;
}

.logo {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: var(--oms-primary);
  color: white;
  font-weight: 800;
  font-size: 18px;
  margin-bottom: 20px;
}

.login-title {
  margin: 0 0 6px;
  color: var(--oms-heading);
  font-size: 22px;
  font-weight: 760;
  letter-spacing: -0.02em;
}

.login-sub {
  margin: 0 0 28px;
  color: var(--oms-sub);
  font-size: 13.5px;
  line-height: 1.5;
}

.login-btn {
  width: 100%;
  margin-top: 4px;
}

.login-footer {
  text-align: center;
  margin-top: 20px;
  color: var(--oms-muted);
  font-size: 12px;
}
</style>
