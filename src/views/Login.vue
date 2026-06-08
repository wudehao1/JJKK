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
const countdown = ref(0)

const phoneValid = computed(() => /^1[3-9]\d{9}$/.test(phone.value))

async function doSendCode() {
  if (!phoneValid.value) { toast.error('请输入正确的手机号'); return }
  try {
    await sendSmsCode(phone.value)
    countdown.value = 60; toast.success('验证码已发送（开发环境：123456）')
    const timer = setInterval(() => { countdown.value--; if (countdown.value <= 0) clearInterval(timer) }, 1000)
  } catch (e: any) { toast.error(e.message || '发送失败') }
}

async function doPhoneLogin() {
  if (!phoneValid.value) { toast.error('请输入正确的手机号'); return }
  if (!code.value) { toast.error('请输入验证码'); return }
  loading.value = true
  try { await auth.login(phone.value, code.value); toast.success('登录成功'); setTimeout(() => goRedirect(), 400) }
  catch (e: any) { toast.error(e.message || '登录失败') }
  finally { loading.value = false }
}

async function doDevLogin() {
  loading.value = true
  try { await auth.quickLogin(); toast.success('登录成功'); setTimeout(() => goRedirect(), 400) }
  catch (e: any) { toast.error(e.message || '登录失败') }
  finally { loading.value = false }
}

function goRedirect() { router.replace((route.query.redirect as string) || '/') }
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="logo">基金快看</div>
      <p class="sub">登录后同步自选和持仓数据</p>

      <div class="tabs">
        <button class="tab" :class="{ active: loginMode === 'phone' }" @click="loginMode = 'phone'">手机号</button>
        <button class="tab" :class="{ active: loginMode === 'wechat' }" @click="loginMode = 'wechat'">微信</button>
      </div>

      <div v-if="loginMode === 'phone'" class="form">
        <input v-model="phone" class="input" type="tel" placeholder="手机号" maxlength="11" />
        <div class="code-row">
          <input v-model="code" class="input code-input" type="text" placeholder="验证码" maxlength="6" @keyup.enter="doPhoneLogin" />
          <button class="code-btn" :disabled="countdown > 0" @click="doSendCode">{{ countdown > 0 ? countdown + 's' : '获取验证码' }}</button>
        </div>
        <button class="submit-btn" :disabled="loading || !phoneValid" @click="doPhoneLogin">{{ loading ? '...' : '登录' }}</button>
        <p class="hint">未注册手机号自动创建，小程序数据通过手机号同步</p>
      </div>

      <div v-else class="form">
        <div class="qr-area">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="color:var(--color-text-tertiary)">
            <path d="M8 12a1 1 0 100-2 1 1 0 000 2zm4 0a1 1 0 100-2 1 1 0 000 2zm4 0a1 1 0 100-2 1 1 0 000 2z"/>
            <path d="M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/>
          </svg>
          <p class="qr-text">微信扫码登录</p>
          <p class="qr-hint">自动同步小程序数据</p>
        </div>
      </div>

      <div class="divider"><span>或</span></div>
      <button class="dev-btn" :disabled="loading" @click="doDevLogin">快速登录</button>
    </div>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--color-bg); padding: 20px; }
.login-card { width: 100%; max-width: 360px; padding: 32px 24px; background: var(--color-bg-card); border-radius: var(--radius-lg); text-align: center; box-shadow: 0 2px 12px var(--color-shadow); }
.logo { font-size: 22px; font-weight: 800; color: var(--color-text-primary); letter-spacing: -0.5px; }
.sub { font-size: 12px; color: var(--color-text-tertiary); margin: 6px 0 20px; }
.tabs { display: flex; gap: 2px; padding: 3px; background: var(--color-bg-secondary); border-radius: var(--radius); margin-bottom: 20px; }
.tab { flex: 1; border: none; border-radius: 6px; padding: 8px 0; background: transparent; color: var(--color-text-tertiary); font-size: 13px; font-weight: 600; }
.tab.active { background: var(--color-bg-card); color: var(--color-primary); box-shadow: 0 1px 2px var(--color-shadow); }
.form { text-align: left; }
.input {
  width: 100%; height: 42px; border: 1px solid var(--color-border); border-radius: var(--radius);
  padding: 0 12px; font-size: 14px; color: var(--color-text-primary); background: var(--color-bg); outline: none; box-sizing: border-box; margin-bottom: 10px;
}
.input:focus { border-color: var(--color-primary); }
.code-row { display: flex; gap: 8px; }
.code-input { flex: 1; }
.code-btn { height: 42px; padding: 0 14px; border: none; border-radius: var(--radius); background: var(--color-primary); color: #fff; font-size: 12px; font-weight: 600; white-space: nowrap; }
.code-btn:disabled { background: var(--color-bg-secondary); color: var(--color-text-tertiary); }
.submit-btn { width: 100%; height: 42px; border: none; border-radius: var(--radius); background: var(--color-primary); color: #fff; font-size: 14px; font-weight: 700; margin-top: 4px; }
.submit-btn:disabled { opacity: 0.5; }
.hint { font-size: 11px; color: var(--color-text-tertiary); margin-top: 10px; text-align: center; line-height: 1.5; }
.qr-area { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 24px 0; }
.qr-text { font-size: 14px; font-weight: 600; color: var(--color-text-secondary); }
.qr-hint { font-size: 11px; color: var(--color-text-tertiary); }
.divider { display: flex; align-items: center; gap: 12px; margin: 18px 0; color: var(--color-text-tertiary); font-size: 11px; }
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: var(--color-divider); }
.dev-btn { width: 100%; height: 38px; border: 1px solid var(--color-border); border-radius: var(--radius); background: transparent; color: var(--color-text-secondary); font-size: 13px; }
.dev-btn:disabled { opacity: 0.5; }
</style>
