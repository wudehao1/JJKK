<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { searchFunds } from '@/api/fund'
import type { FundSearchResult } from '@/types'

const router = useRouter()
const route = useRoute()
const keyword = ref('')
const results = ref<FundSearchResult[]>([])
const loading = ref(false)
const searched = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  const initQ = route.query.q
  if (initQ && typeof initQ === 'string') {
    keyword.value = initQ
    doSearch()
  }
})

function onInput() {
  if (timer) clearTimeout(timer)
  if (!keyword.value.trim()) { results.value = []; searched.value = false; return }
  timer = setTimeout(doSearch, 300)
}

async function doSearch() {
  const kw = keyword.value.trim(); if (!kw) return
  loading.value = true; searched.value = true
  try { results.value = (await searchFunds(kw, 1, 20))?.items || [] } catch { results.value = [] } finally { loading.value = false }
}

function goFund(code: string) { router.push('/fund/' + code) }
function fmtReturn(val?: number) { if (val === null || val === undefined) return '--'; return (val > 0 ? '+' : '') + val.toFixed(2) + '%' }
function retClass(val?: number) { if (!val) return 'flat'; return val > 0 ? 'up' : 'down' }
</script>

<template>
  <div class="page">
    <div class="search-bar">
      <svg class="si" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
      <input v-model="keyword" class="search-input" placeholder="搜索基金名称或代码" autofocus @input="onInput" @keyup.enter="doSearch" />
    </div>

    <div v-if="loading" class="status">搜索中...</div>

    <div v-else-if="results.length" class="result-list">
      <div v-for="fund in results" :key="fund.fundCode" class="result-item" @click="goFund(fund.fundCode)">
        <div class="result-info">
          <div class="result-name">{{ fund.fundName }}</div>
          <div class="result-meta">{{ fund.fundCode }} · {{ fund.companyName }}</div>
        </div>
        <div class="result-data">
          <span class="result-nav">{{ fund.unitNav?.toFixed(4) || '--' }}</span>
          <span class="result-pct" :class="retClass(fund.dailyReturnPct)">{{ fmtReturn(fund.dailyReturnPct) }}</span>
        </div>
      </div>
    </div>

    <div v-else-if="searched" class="status">未找到相关基金</div>
    <div v-else class="status">输入基金名称或代码搜索</div>
  </div>
</template>

<style scoped>
.search-bar {
  display: flex; align-items: center; gap: 8px;
  margin-bottom: 16px; height: 40px;
  background: var(--color-bg-card); border-radius: var(--radius-lg);
  padding: 0 14px; box-shadow: 0 1px 3px var(--color-shadow);
}
.si { color: var(--color-text-tertiary); flex-shrink: 0; }
.search-input {
  flex: 1; height: 100%; border: none; background: transparent;
  font-size: 14px; color: var(--color-text-primary); outline: none;
}
.search-input::placeholder { color: var(--color-text-tertiary); }
.status { text-align: center; padding: 40px; color: var(--color-text-tertiary); font-size: 13px; }
.result-list { background: var(--color-bg-card); border-radius: var(--radius-lg); overflow: hidden; box-shadow: 0 1px 3px var(--color-shadow); }
.result-item {
  display: flex; align-items: center; padding: 12px 14px;
  border-bottom: 1px solid var(--color-divider); cursor: pointer;
}
.result-item:last-child { border-bottom: none; }
.result-item:hover { background: var(--color-bg-hover); }
.result-info { flex: 1; min-width: 0; }
.result-name { font-size: 14px; font-weight: 600; color: var(--color-text-primary); }
.result-meta { font-size: 11px; color: var(--color-text-tertiary); margin-top: 2px; }
.result-data { text-align: right; }
.result-nav { font-size: 13px; font-weight: 600; color: var(--color-text-primary); display: block; font-variant-numeric: tabular-nums; }
.result-pct { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.result-pct.up { color: var(--color-up); }
.result-pct.down { color: var(--color-down); }
.result-pct.flat { color: var(--color-text-secondary); }
</style>
