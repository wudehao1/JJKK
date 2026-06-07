<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { searchFunds } from '@/api/fund'
import type { FundSearchResult } from '@/types'

const router = useRouter()
const keyword = ref('')
const results = ref<FundSearchResult[]>([])
const loading = ref(false)
const searched = ref(false)

let timer: ReturnType<typeof setTimeout> | null = null

function onInput() {
  if (timer) clearTimeout(timer)
  if (!keyword.value.trim()) {
    results.value = []
    searched.value = false
    return
  }
  timer = setTimeout(doSearch, 300)
}

async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) return
  loading.value = true
  searched.value = true
  try {
    const page = await searchFunds(kw, 1, 20)
    results.value = page?.items || []
  } catch {
    results.value = []
  } finally {
    loading.value = false
  }
}

function goFund(code: string) {
  router.push('/fund/' + code)
}

function goBack() {
  router.back()
}
</script>

<template>
  <div class="page search-page">
    <div class="search-bar">
      <button class="back-btn" @click="goBack">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5m7-7l-7 7 7 7"/></svg>
      </button>
      <input
        v-model="keyword"
        class="search-input"
        placeholder="搜索基金名称或代码"
        autofocus
        @input="onInput"
        @keyup.enter="doSearch"
      />
    </div>

    <div v-if="loading" class="loading-state">搜索中...</div>

    <div v-else-if="results.length" class="result-list">
      <div v-for="fund in results" :key="fund.fundCode" class="result-item" @click="goFund(fund.fundCode)">
        <div class="result-left">
          <div class="result-name">{{ fund.fundName }}</div>
          <div class="result-meta">{{ fund.fundCode }} · {{ fund.companyName }}</div>
        </div>
        <div class="result-right">
          <span class="result-nav">{{ fund.unitNav?.toFixed(4) || '--' }}</span>
          <span class="result-return" :class="(fund.dailyReturnPct || 0) > 0 ? 'up' : (fund.dailyReturnPct || 0) < 0 ? 'down' : 'flat'">
            {{ fund.dailyReturnPct != null ? ((fund.dailyReturnPct > 0 ? '+' : '') + fund.dailyReturnPct.toFixed(2) + '%') : '--' }}
          </span>
        </div>
      </div>
    </div>

    <div v-else-if="searched" class="empty-state">未找到相关基金</div>
    <div v-else class="empty-state">输入基金名称或代码搜索</div>
  </div>
</template>

<style scoped>
.search-page { padding-bottom: 20px; }
.search-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 16px; background: var(--color-bg-card);
  border-bottom: 1px solid var(--color-border);
}
.back-btn {
  width: 36px; height: 36px; border: none; border-radius: 8px;
  background: transparent; color: var(--color-text-secondary);
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.search-input {
  flex: 1; height: 38px; border: none; border-radius: 19px;
  background: var(--color-bg-secondary); padding: 0 14px;
  font-size: 14px; color: var(--color-text-primary); outline: none;
}
.search-input::placeholder { color: var(--color-text-tertiary); }
.loading-state, .empty-state { text-align: center; padding: 40px; color: var(--color-text-secondary); font-size: 14px; }
.result-list { background: var(--color-bg-card); }
.result-item {
  display: flex; align-items: center; padding: 12px 16px;
  border-bottom: 1px solid var(--color-border); cursor: pointer;
}
.result-item:hover { background: var(--color-bg-hover); }
.result-left { flex: 1; min-width: 0; }
.result-name { font-size: 14px; font-weight: 600; color: var(--color-text-primary); }
.result-meta { font-size: 12px; color: var(--color-text-secondary); margin-top: 2px; }
.result-right { text-align: right; }
.result-nav { font-size: 14px; color: var(--color-text-primary); display: block; }
.result-return { font-size: 13px; font-weight: 700; }
.result-return.up { color: var(--color-up); }
.result-return.down { color: var(--color-down); }
.result-return.flat { color: var(--color-text-secondary); }
</style>
