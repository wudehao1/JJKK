<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const navItems = [
  { path: '/', label: '大盘行情', icon: 'M3 3v18h18' },
  { path: '/watchlist', label: '自选基金', icon: 'M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z' },
  { path: '/holdings', label: '我的持仓', icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
  { path: '/information', label: '资讯', icon: 'M19 20H5a2 2 0 01-2-2V6a2 2 0 012-2h10a2 2 0 012 2v1m2 13a2 2 0 01-2-2V7m2 13a2 2 0 002-2V9a2 2 0 00-2-2h-2m-4-3H9M7 16h6M7 8h6v4H7V8z' },
  { path: '/sector-rankings', label: '板块排行', icon: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6' },
  { path: '/profit-analysis', label: '盈亏分析', icon: 'M23 6l-9.5 9.5-5-5L1 18' },
]

function isActive(path: string) { return route.path === path }
function go(path: string) { router.push(path) }
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-logo" @click="go('/')">基金快看</div>
    <nav class="sidebar-nav">
      <div v-for="item in navItems" :key="item.path" class="nav-item" :class="{ active: isActive(item.path) }" @click="go(item.path)">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="item.icon" /></svg>
        <span class="nav-label">{{ item.label }}</span>
      </div>
    </nav>
    <div class="sidebar-footer">
      <div class="nav-item" @click="go('/me')">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" /></svg>
        <span class="nav-label">{{ auth.isLoggedIn ? auth.nickname : '登录' }}</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  position: fixed; left: 0; top: 0; bottom: 0; width: 200px;
  background: var(--color-bg-card); border-right: 1px solid var(--color-border);
  display: flex; flex-direction: column; z-index: 50;
}
.sidebar-logo {
  padding: 16px 20px; font-size: 16px; font-weight: 800;
  color: var(--color-primary); cursor: pointer; letter-spacing: -0.5px;
  border-bottom: 1px solid var(--color-divider);
}
.sidebar-nav { flex: 1; padding: 8px 8px; overflow-y: auto; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 12px; border-radius: 6px; cursor: pointer;
  color: var(--color-text-secondary); font-size: 13px; font-weight: 500;
  transition: all 0.15s; margin-bottom: 2px;
}
.nav-item:hover { background: var(--color-bg-secondary); color: var(--color-text-primary); }
.nav-item.active { background: var(--color-primary-light); color: var(--color-primary); font-weight: 600; }
.nav-icon { width: 18px; height: 18px; flex-shrink: 0; }
.sidebar-footer { padding: 8px; border-top: 1px solid var(--color-divider); }
</style>
