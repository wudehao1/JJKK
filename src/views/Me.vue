<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { useTheme } from '@/composables/useTheme'

const auth = useAuthStore()
const router = useRouter()
const { mode, setMode } = useTheme()

function goLogin() { router.push('/login') }
function goProfit() { router.push('/profit-analysis') }
function doLogout() { auth.logout(); router.replace('/') }
</script>

<template>
  <div class="page me-page">
    <div class="page-header"><h1 class="page-title">我的</h1></div>

    <!-- User info -->
    <div class="user-card">
      <div class="avatar">{{ auth.nickname ? auth.nickname[0] : '?' }}</div>
      <div class="user-info">
        <div class="user-name">{{ auth.isLoggedIn ? auth.nickname : '未登录' }}</div>
        <div class="user-id" v-if="auth.isLoggedIn">ID: {{ auth.userId }}</div>
      </div>
      <button v-if="!auth.isLoggedIn" class="action-btn" @click="goLogin">登录</button>
      <button v-else class="action-btn logout" @click="doLogout">退出</button>
    </div>

    <!-- Menu items -->
    <div class="menu-section">
      <div class="menu-item" @click="goProfit">
        <span class="menu-icon">&#128200;</span>
        <span class="menu-label">盈亏分析</span>
        <span class="menu-arrow">&#8250;</span>
      </div>
    </div>

    <!-- Theme toggle -->
    <div class="menu-section">
      <div class="menu-item">
        <span class="menu-icon">&#127769;</span>
        <span class="menu-label">深色模式</span>
        <div class="theme-switch">
          <button :class="{ active: mode === 'light' }" @click="setMode('light')">浅色</button>
          <button :class="{ active: mode === 'dark' }" @click="setMode('dark')">深色</button>
          <button :class="{ active: mode === 'system' }" @click="setMode('system')">跟随</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.me-page { padding-bottom: 72px; }
.page-header { padding: 16px 16px 8px; }
.page-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); margin: 0; }
.user-card {
  display: flex; align-items: center; gap: 12px;
  margin: 8px 16px; padding: 16px; border-radius: 12px;
  background: var(--color-bg-card);
}
.avatar {
  width: 48px; height: 48px; border-radius: 24px;
  background: var(--color-primary); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 20px; font-weight: 800;
}
.user-info { flex: 1; }
.user-name { font-size: 16px; font-weight: 700; color: var(--color-text-primary); }
.user-id { font-size: 12px; color: var(--color-text-secondary); margin-top: 2px; }
.action-btn {
  border: none; border-radius: 8px; padding: 8px 16px;
  background: var(--color-primary); color: #fff;
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.action-btn.logout { background: var(--color-bg-secondary); color: var(--color-text-secondary); }
.menu-section { margin: 8px 16px; background: var(--color-bg-card); border-radius: 10px; overflow: hidden; }
.menu-item {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px; border-bottom: 1px solid var(--color-border);
  cursor: pointer;
}
.menu-item:last-child { border-bottom: none; }
.menu-icon { font-size: 18px; }
.menu-label { flex: 1; font-size: 14px; color: var(--color-text-primary); }
.menu-arrow { color: var(--color-text-tertiary); font-size: 18px; }
.theme-switch {
  display: flex; gap: 2px; background: var(--color-bg-secondary); border-radius: 6px; padding: 2px;
}
.theme-switch button {
  border: none; border-radius: 4px; padding: 4px 10px;
  background: transparent; color: var(--color-text-secondary);
  font-size: 12px; cursor: pointer;
}
.theme-switch button.active { background: var(--color-bg-card); color: var(--color-primary); font-weight: 600; }
</style>
