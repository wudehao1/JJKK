<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const phone = ref('')
const code = ref('')
const loading = ref(false)
const codeSent = ref(false)
const countdown = ref(0)

async function sendCode() {
  if (!phone.value || phone.value.length < 11) {
    alert('请输入正确的手机号')
    return
  }
  codeSent.value = true
  countdown.value = 60
  const timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

async function doLogin() {
  loading.value = true
  try {
    await auth.login(phone.value, code.value)
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  } catch (e: any) {
    alert(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}

async function devLogin() {
  loading.value = true
  try {
    await auth.quickLogin()
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
      <h1 class="login-title">基金快看</h1>
      <p class="login-sub">登录后查看自选和持仓</p>

      <div class="form-group">
        <input v-model="phone" class="form-input" type="tel" placeholder="手机号" maxlength="11" />
      </div>

      <div class="form-group code-group">
        <input v-model="code" class="form-input code-input" type="text" placeholder="验证码" maxlength="6" />
        <button class="code-btn" :disabled="countdown > 0" @click="sendCode">
          {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
        </button>
      </div>

      <button class="login-btn" :disabled="loading" @click="doLogin">
        {{ loading ? '登录中...' : '登录' }}
      </button>

      <div class="divider"><span>或</span></div>

      <button class="dev-btn" :disabled="loading" @click="devLogin">开发环境快速登录</button>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--color-bg); padding: 20px;
}
.login-card {
  width: 100%; max-width: 360px; padding: 32px 24px;
  background: var(--color-bg-card); border-radius: 16px;
}
.login-title { font-size: 24px; font-weight: 800; color: var(--color-text-primary); text-align: center; margin: 0; }
.login-sub { font-size: 13px; color: var(--color-text-secondary); text-align: center; margin: 8px 0 24px; }
.form-group { margin-bottom: 12px; }
.form-input {
  width: 100%; height: 44px; border: 1px solid var(--color-border); border-radius: 10px;
  padding: 0 14px; font-size: 14px; color: var(--color-text-primary);
  background: var(--color-bg); outline: none; box-sizing: border-box;
}
.form-input:focus { border-color: var(--color-primary); }
.code-group { display: flex; gap: 8px; }
.code-input { flex: 1; }
.code-btn {
  height: 44px; padding: 0 14px; border: none; border-radius: 10px;
  background: var(--color-bg-secondary); color: var(--color-primary);
  font-size: 13px; white-space: nowrap; cursor: pointer;
}
.code-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.login-btn {
  width: 100%; height: 44px; border: none; border-radius: 10px;
  background: var(--color-primary); color: #fff;
  font-size: 15px; font-weight: 700; cursor: pointer; margin-top: 4px;
}
.login-btn:disabled { opacity: 0.6; }
.divider {
  display: flex; align-items: center; gap: 12px;
  margin: 20px 0; color: var(--color-text-tertiary); font-size: 12px;
}
.divider::before, .divider::after {
  content: ''; flex: 1; height: 1px; background: var(--color-border);
}
.dev-btn {
  width: 100%; height: 40px; border: 1px solid var(--color-border); border-radius: 10px;
  background: transparent; color: var(--color-text-secondary);
  font-size: 13px; cursor: pointer;
}
</style>
