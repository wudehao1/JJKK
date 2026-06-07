<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const nickname = ref('')
const loading = ref(false)

async function doLogin() {
  loading.value = true
  try {
    await auth.quickLogin(nickname.value || undefined)
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  } catch (e: any) {
    alert(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">基金快看</div>
      <p class="login-sub">Web 版 · 登录后查看自选和持仓</p>

      <div class="form-group">
        <input v-model="nickname" class="form-input" type="text" placeholder="输入昵称（可选）" maxlength="20" />
      </div>

      <button class="login-btn" :disabled="loading" @click="doLogin">
        {{ loading ? '登录中...' : '一键登录' }}
      </button>

      <p class="login-hint">开发环境免注册快速登录</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--color-bg); padding: 20px;
}
.login-card {
  width: 100%; max-width: 360px; padding: 40px 28px;
  background: var(--color-bg-card); border-radius: 16px;
  text-align: center;
}
.login-logo {
  font-size: 28px; font-weight: 900; color: var(--color-primary);
  letter-spacing: -0.5px;
}
.login-sub { font-size: 13px; color: var(--color-text-secondary); margin: 10px 0 28px; }
.form-group { margin-bottom: 16px; }
.form-input {
  width: 100%; height: 46px; border: 1px solid var(--color-border); border-radius: 10px;
  padding: 0 14px; font-size: 14px; color: var(--color-text-primary);
  background: var(--color-bg); outline: none; box-sizing: border-box;
  text-align: center;
}
.form-input:focus { border-color: var(--color-primary); }
.login-btn {
  width: 100%; height: 46px; border: none; border-radius: 10px;
  background: var(--color-primary); color: #fff;
  font-size: 16px; font-weight: 700; cursor: pointer;
}
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.login-hint { font-size: 12px; color: var(--color-text-tertiary); margin-top: 16px; }
</style>
