<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMarketOverview, getSectorRankings, getFundRankings } from '@/api/market'
import type { MarketOverview, SectorRanking, FundRanking } from '@/types'

const router = useRouter()
const overview = ref<MarketOverview | null>(null)
const sectors = ref<SectorRanking[]>([])
const rankings = ref<FundRanking[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const [ov, sec, rank] = await Promise.all([
      getMarketOverview(),
      getSectorRankings(8),
      getFundRankings(10)
    ])
    overview.value = ov
    sectors.value = sec || []
    rankings.value = rank || []
  } catch {
    // silent
  } finally {
    loading.value = false
  }
})

function formatPct(val: number) {
  const sign = val > 0 ? '+' : ''
  return sign + val.toFixed(2) + '%'
}

function pctClass(val: number) {
  if (val > 0) return 'up'
  if (val < 0) return 'down'
  return 'flat'
}

function goFund(code: string) {
  router.push('/fund/' + code)
}

function goSearch() {
  router.push('/search')
}
</script>

<template>
  <div class="page home-page">
    <div class="page-header">
      <h1 class="page-title">大盘行情</h1>
      <div class="header-actions">
        <button class="icon-btn" @click="goSearch">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
        </button>
      </div>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <template v-else>
      <!-- Index cards -->
      <div class="index-cards" v-if="overview">
        <div
          v-for="idx in overview.indices"
          :key="idx.symbol"
          class="index-card"
          :class="pctClass(idx.changePct)"
        >
          <div class="index-name">{{ idx.name }}</div>
          <div class="index-value">{{ idx.latest?.toFixed(2) || '--' }}</div>
          <div class="index-change">
            <span>{{ idx.change > 0 ? '+' : '' }}{{ idx.change?.toFixed(2) || '0.00' }}</span>
            <span class="index-pct">{{ formatPct(idx.changePct || 0) }}</span>
          </div>
        </div>
      </div>

      <!-- Market stats -->
      <div class="market-stats" v-if="overview">
        <div class="stat-item">
          <span class="stat-label">上涨</span>
          <span class="stat-value up">{{ overview.riseCount }}</span>
        </div>
        <div class="stat-item">
          <span class="stat-label">下跌</span>
          <span class="stat-value down">{{ overview.fallCount }}</span>
        </div>
      </div>

      <!-- Sector rankings -->
      <div class="section" v-if="sectors.length">
        <div class="section-title">板块涨幅</div>
        <div class="sector-list">
          <div v-for="sec in sectors" :key="sec.sectorCode" class="sector-item">
            <span class="sector-name">{{ sec.sectorName }}</span>
            <span class="sector-return" :class="pctClass(sec.avgReturnPct)">{{ formatPct(sec.avgReturnPct) }}</span>
          </div>
        </div>
      </div>

      <!-- Fund rankings -->
      <div class="section" v-if="rankings.length">
        <div class="section-title">基金涨幅榜</div>
        <div class="ranking-list">
          <div
            v-for="(fund, i) in rankings"
            :key="fund.fundCode"
            class="ranking-item"
            @click="goFund(fund.fundCode)"
          >
            <span class="rank-no" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span>
            <div class="rank-info">
              <div class="rank-name">{{ fund.fundName }}</div>
              <div class="rank-code">{{ fund.fundCode }}</div>
            </div>
            <div class="rank-right">
              <span class="rank-nav">{{ fund.unitNav?.toFixed(4) || '--' }}</span>
              <span class="rank-return" :class="pctClass(fund.estimateReturnPct)">{{ formatPct(fund.estimateReturnPct || 0) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.home-page { padding-bottom: 72px; }
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.page-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); margin: 0; }
.icon-btn {
  width: 36px; height: 36px; border: none; border-radius: 8px;
  background: var(--color-bg-card); color: var(--color-text-secondary);
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.loading-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }

.index-cards {
  display: flex;
  gap: 8px;
  padding: 8px 16px;
  overflow-x: auto;
}
.index-card {
  flex: 1; min-width: 100px;
  padding: 12px; border-radius: 10px;
  background: var(--color-bg-card);
  text-align: center;
}
.index-card.up { background: linear-gradient(135deg, #FEF2F2, #FEE2E2); }
.index-card.down { background: linear-gradient(135deg, #F0FDF4, #DCFCE7); }
.index-name { font-size: 12px; color: var(--color-text-secondary); }
.index-value { font-size: 18px; font-weight: 800; margin: 4px 0 2px; color: var(--color-text-primary); }
.index-change { font-size: 12px; display: flex; gap: 6px; justify-content: center; }
.index-card.up .index-change { color: var(--color-up); }
.index-card.down .index-change { color: var(--color-down); }
.index-pct { font-weight: 700; }

.market-stats {
  display: flex; gap: 12px; padding: 8px 16px;
}
.stat-item {
  flex: 1; display: flex; align-items: center; gap: 6px;
  padding: 10px 12px; border-radius: 8px;
  background: var(--color-bg-card); font-size: 14px;
}
.stat-label { color: var(--color-text-secondary); }
.stat-value.up { color: var(--color-up); font-weight: 700; }
.stat-value.down { color: var(--color-down); font-weight: 700; }

.section { padding: 12px 16px; }
.section-title { font-size: 15px; font-weight: 700; color: var(--color-text-primary); margin-bottom: 8px; }

.sector-list { background: var(--color-bg-card); border-radius: 10px; overflow: hidden; }
.sector-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--color-border);
}
.sector-item:last-child { border-bottom: none; }
.sector-name { font-size: 13px; color: var(--color-text-primary); }
.sector-return { font-size: 13px; font-weight: 700; }
.sector-return.up { color: var(--color-up); }
.sector-return.down { color: var(--color-down); }
.sector-return.flat { color: var(--color-text-secondary); }

.ranking-list { background: var(--color-bg-card); border-radius: 10px; overflow: hidden; }
.ranking-item {
  display: flex; align-items: center; padding: 10px 14px; gap: 10px;
  border-bottom: 1px solid var(--color-border); cursor: pointer;
}
.ranking-item:last-child { border-bottom: none; }
.ranking-item:hover { background: var(--color-bg-hover); }
.rank-no {
  width: 22px; height: 22px; border-radius: 6px; font-size: 12px; font-weight: 800;
  display: flex; align-items: center; justify-content: center;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
}
.rank-no.top { background: #FEF3C7; color: #D97706; }
.rank-info { flex: 1; min-width: 0; }
.rank-name { font-size: 13px; font-weight: 600; color: var(--color-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rank-code { font-size: 11px; color: var(--color-text-secondary); }
.rank-right { text-align: right; }
.rank-nav { font-size: 13px; color: var(--color-text-primary); display: block; }
.rank-return { font-size: 13px; font-weight: 700; }
.rank-return.up { color: var(--color-up); }
.rank-return.down { color: var(--color-down); }
.rank-return.flat { color: var(--color-text-secondary); }
</style>
