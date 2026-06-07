<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getHoldingDashboard } from '@/api/user'
import { useRouter } from 'vue-router'
import type { HoldingDashboard as DashboardType } from '@/types'

const auth = useAuthStore()
const router = useRouter()
const dashboard = ref<DashboardType | null>(null)
const loading = ref(true)

const summary = computed(() => dashboard.value?.summary)
const holdings = computed(() => dashboard.value?.holdings || [])

onMounted(async () => {
  if (!auth.isLoggedIn) return
  try {
    dashboard.value = await getHoldingDashboard(auth.userId)
  } catch { /* silent */ } finally {
    loading.value = false
  }
})

function fmt(val?: number) {
  if (val === null || val === undefined) return '--'
  return val.toFixed(2)
}

function fmtPct(val?: number) {
  if (val === null || val === undefined) return '--'
  return (val > 0 ? '+' : '') + val.toFixed(2) + '%'
}

function valClass(val?: number) {
  if (!val) return 'flat'
  return val > 0 ? 'up' : 'down'
}

function goFund(code: string) {
  router.push('/fund/' + code)
}
</script>

<template>
  <div class="page holdings-page">
    <div class="page-header">
      <h1 class="page-title">我的持仓</h1>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <template v-else>
      <!-- Summary card -->
      <div class="summary-card" v-if="summary">
        <div class="summary-row">
          <div class="summary-item">
            <div class="summary-label">总市值</div>
            <div class="summary-value">{{ fmt(summary.totalMarketValue) }}</div>
          </div>
          <div class="summary-item">
            <div class="summary-label">总成本</div>
            <div class="summary-value">{{ fmt(summary.totalCost) }}</div>
          </div>
        </div>
        <div class="summary-row">
          <div class="summary-item">
            <div class="summary-label">总盈亏</div>
            <div class="summary-value" :class="valClass(summary.totalProfitLoss)">
              {{ fmt(summary.totalProfitLoss) }}
            </div>
          </div>
          <div class="summary-item">
            <div class="summary-label">收益率</div>
            <div class="summary-value" :class="valClass(summary.totalProfitLossPct)">
              {{ fmtPct(summary.totalProfitLossPct) }}
            </div>
          </div>
        </div>
      </div>

      <!-- Holdings list -->
      <div class="section" v-if="holdings.length">
        <div class="section-title">持仓明细</div>
        <div class="holding-list">
          <div v-for="h in holdings" :key="h.id" class="holding-item" @click="goFund(h.fundCode)">
            <div class="holding-top">
              <span class="holding-name">{{ h.fundName }}</span>
              <span class="holding-pnl" :class="valClass(h.profitLossAmount)">{{ fmtPct(h.profitLossPct) }}</span>
            </div>
            <div class="holding-bottom">
              <span class="holding-code">{{ h.fundCode }}</span>
              <span class="holding-amount">盈亏 {{ fmt(h.profitLossAmount) }}</span>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="empty-state">暂无持仓记录</div>
    </template>
  </div>
</template>

<style scoped>
.holdings-page { padding-bottom: 72px; }
.page-header { padding: 16px 16px 8px; }
.page-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); margin: 0; }
.loading-state, .empty-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
.summary-card {
  margin: 8px 16px; padding: 16px; border-radius: 12px;
  background: var(--color-bg-card);
}
.summary-row { display: flex; gap: 12px; }
.summary-row + .summary-row { margin-top: 12px; }
.summary-item { flex: 1; }
.summary-label { font-size: 12px; color: var(--color-text-secondary); }
.summary-value { font-size: 18px; font-weight: 800; color: var(--color-text-primary); margin-top: 2px; }
.summary-value.up { color: var(--color-up); }
.summary-value.down { color: var(--color-down); }
.section { padding: 12px 16px; }
.section-title { font-size: 15px; font-weight: 700; color: var(--color-text-primary); margin-bottom: 8px; }
.holding-list { background: var(--color-bg-card); border-radius: 10px; overflow: hidden; }
.holding-item {
  padding: 12px 14px; border-bottom: 1px solid var(--color-border);
  cursor: pointer;
}
.holding-item:hover { background: var(--color-bg-hover); }
.holding-item:last-child { border-bottom: none; }
.holding-top { display: flex; justify-content: space-between; align-items: center; }
.holding-name { font-size: 14px; font-weight: 600; color: var(--color-text-primary); }
.holding-pnl { font-size: 14px; font-weight: 700; }
.holding-pnl.up { color: var(--color-up); }
.holding-pnl.down { color: var(--color-down); }
.holding-bottom { display: flex; justify-content: space-between; margin-top: 4px; }
.holding-code { font-size: 12px; color: var(--color-text-secondary); }
.holding-amount { font-size: 12px; color: var(--color-text-secondary); }
</style>
