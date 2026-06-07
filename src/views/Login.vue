<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { sendSmsCode } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const toast = useToast()

const loginMode = ref<'phone' | 'wechat'>('phone')
const phone = ref('')
const code = ref('')
const loading = ref(false)
const codeSent = ref(false)
const countdown = ref(0)

const phoneValid = computed(() => /^1[3-9]\d{9}$/.test(phone.value))

async function doSendCode() {
  if (!phoneValid.value) { toast.error('请输入正确的手机号'); return }
  try {
    await sendSmsCode(phone.value)
    codeSent.value = true
    countdown.value = 60
    toast.success('验证码已发送（开发环境：123456）')
    const timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e: any) {
    toast.error(e.message || '发送失败')
  }
}

async function doPhoneLogin() {
  if (!phoneValid.value) { toast.error('请输入正确的手机号'); return }
  if (!code.value) { toast.error('请输入验证码'); return }
  loading.value = true
  try {
    await auth.login(phone.value, code.value)
    toast.success('登录成功')
    setTimeout(() => goRedirect(), 500)
  } catch (e: any) {
    toast.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}

async function doDevLogin() {
  loading.value = true
  try {
    await auth.quickLogin()
    toast.success('登录成功')
    setTimeout(() => goRedirect(), 500)
  } catch (e: any) {
    toast.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}

function goRedirect() {
  const redirect = (route.query.redirect as string) || '/'
  router.replace(redirect)
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">基金快看</div>
      <p class="login-sub">Web 版 · 登录后同步自选和持仓数据</p>

      <!-- Mode tabs -->
      <div class="mode-tabs">
        <button class="mode-tab" :class="{ active: loginMode === 'phone' }" @click="loginMode = 'phone'">手机号登录</button>
        <button class="mode-tab" :class="{ active: loginMode === 'wechat' }" @click="loginMode = 'wechat'">微信登录</button>
      </div>

      <!-- Phone login -->
      <div v-if="loginMode === 'phone'" class="login-form">
        <div class="form-group">
          <input v-model="phone" class="form-input" type="tel" placeholder="输入手机号" maxlength="11" />
        </div>
        <div class="form-group code-group">
          <input v-model="code" class="form-input code-input" type="text" placeholder="验证码" maxlength="6" @keyup.enter="doPhoneLogin" />
          <button class="code-btn" :disabled="countdown > 0" @click="doSendCode">
            {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
          </button>
        </div>
        <button class="login-btn" :disabled="loading || !phoneValid" @click="doPhoneLogin">
          {{ loading ? '登录中...' : '登录' }}
        </button>
        <p class="form-hint">未注册手机号将自动创建账号，小程序和Web端数据通过手机号同步</p>
      </div>

      <!-- WeChat login -->
      <div v-else class="login-form">
        <div class="wechat-qr-area">
          <div class="qr-placeholder">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="color:var(--color-text-tertiary)">
              <path d="M8 12a1 1 0 100-2 1 1 0 000 2zm4 0a1 1 0 100-2 1 1 0 000 2zm4 0a1 1 0 100-2 1 1 0 000 2z"/>
              <path d="M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/>
            </svg>
            <p class="qr-text">微信扫码登录</p>
            <p class="qr-hint">使用微信扫一扫，自动同步小程序数据</p>
          </div>
        </div>
        <p class="form-hint">扫码登录后，小程序中的自选和持仓数据将自动同步到 Web 端</p>
      </div>

      <div class="divider"><span>或</span></div>

      <button class="dev-btn" :disabled="loading" @click="doDevLogin">开发环境快速登录</button>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--color-bg); padding: 20px;
}
.login-card {
  width: 100%; max-width: 380px; padding: 36px 28px;
  background: var(--color-bg-card); border-radius: 16px;
  text-align: center;
}
.login-logo {
  font-size: 28px; font-weight: 900; color: var(--color-primary);
  letter-spacing: -0.5px;
}
.login-sub { font-size: 13px; color: var(--color-text-secondary); margin: 8px 0 24px; }

.mode-tabs {
  display: flex; gap: 4px; padding: 4px;
  background: var(--color-bg-secondary); border-radius: 10px; margin-bottom: 20px;
}
.mode-tab {
  flex: 1; border: none; border-radius: 8px; padding: 10px 0;
  background: transparent; color: var(--color-text-secondary);
  font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s;
}
.mode-tab.active { background: var(--color-bg-card); color: var(--color-primary); }

.login-form { text-align: left; }
.form-group { margin-bottom: 12px; }
.form-input {
  width: 100%; height: 46px; border: 1px solid var(--color-border); border-radius: 10px;
  padding: 0 14px; font-size: 14px; color: var(--color-text-primary);
  background: var(--color-bg); outline: none; box-sizing: border-box;
}
.form-input:focus { border-color: var(--color-primary); }
.code-group { display: flex; gap: 8px; }
.code-input { flex: 1; }
.code-btn {
  height: 46px; padding: 0 16px; border: none; border-radius: 10px;
  background: var(--color-primary); color: #fff;
  font-size: 13px; font-weight: 600; white-space: nowrap; cursor: pointer;
}
.code-btn:disabled { opacity: 0.5; cursor: not-allowed; background: var(--color-bg-secondary); color: var(--color-text-secondary); }
.login-btn {
  width: 100%; height: 46px; border: none; border-radius: 10px;
  background: var(--color-primary); color: #fff;
  font-size: 15px; font-weight: 700; cursor: pointer; margin-top: 4px;
}
.login-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.form-hint { font-size: 12px; color: var(--color-text-tertiary); margin-top: 12px; line-height: 1.5; text-align: center; }

.wechat-qr-area {
  display: flex; justify-content: center; padding: 20px 0;
}
.qr-placeholder {
  width: 200px; height: 200px; border: 2px dashed var(--color-border); border-radius: 12px;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
}
.qr-text { font-size: 14px; font-weight: 600; color: var(--color-text-secondary); }
.qr-hint { font-size: 11px; color: var(--color-text-tertiary); padding: 0 16px; text-align: center; }

.divider {
  display: flex; align-items: center; gap: 12px;
  margin: 20px 0; color: var(--color-text-tertiary); font-size: 12px;
}
.divider::before, .divider::after {
  content: ''; flex: 1; height: 1px; background: var(--color-border);
}
.dev-btn {
  width: 100%; height: 42px; border: 1px solid var(--color-border); border-radius: 10px;
  background: transparent; color: var(--color-text-secondary);
  font-size: 13px; cursor: pointer;
}
.dev-btn:disabled { opacity: 0.5; }
</style>
