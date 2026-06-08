<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from '@/composables/useTheme'

const router = useRouter()
const { currentTheme, setMode } = useTheme()
const searchKeyword = ref('')

function doSearch() {
  const kw = searchKeyword.value.trim()
  if (kw) router.push('/search?q=' + encodeURIComponent(kw))
}

function toggleTheme() {
  setMode(currentTheme.value === 'dark' ? 'light' : 'dark')
}
</script>

<template>
  <header class="app-header">
    <div class="header-search">
      <svg class="search-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
      <input v-model="searchKeyword" class="search-input" placeholder="搜索基金名称或代码" @keyup.enter="doSearch" @focus="router.push('/search')" />
    </div>
    <button class="theme-btn" @click="toggleTheme" :title="currentTheme === 'dark' ? '切换浅色' : '切换深色'">
      <svg v-if="currentTheme === 'dark'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><path d="M12 1v2m0 18v2M4.22 4.22l1.42 1.42m12.72 12.72l1.42 1.42M1 12h2m18 0h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>
      <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/></svg>
    </button>
  </header>
</template>

<style scoped>
.app-header {
  position: fixed; top: 0; left: 200px; right: 0; height: 52px;
  background: var(--color-bg-card); border-bottom: 1px solid var(--color-border);
  display: flex; align-items: center; padding: 0 24px; gap: 12px; z-index: 40;
}
.header-search {
  display: flex; align-items: center; gap: 8px;
  flex: 1; max-width: 400px; height: 34px;
  background: var(--color-bg-secondary); border-radius: 6px; padding: 0 12px;
}
.search-icon { color: var(--color-text-tertiary); flex-shrink: 0; }
.search-input {
  flex: 1; border: none; background: transparent; outline: none;
  font-size: 13px; color: var(--color-text-primary);
}
.search-input::placeholder { color: var(--color-text-tertiary); }
.theme-btn {
  width: 34px; height: 34px; border: none; border-radius: 6px;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
  display: flex; align-items: center; justify-content: center;
}
.theme-btn:hover { background: var(--color-border); }
</style>
