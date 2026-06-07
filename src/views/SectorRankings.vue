<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getSectorRankings, getFundRankings } from '@/api/market'
import type { SectorRanking, FundRanking } from '@/types'

const router = useRouter()
const sectors = ref<SectorRanking[]>([])
const funds = ref<FundRanking[]>([])
const tab = ref<'sector' | 'fund'>('sector')
const loading = ref(true)

onMounted(async () => {
  try {
    const [s, f] = await Promise.all([getSectorRankings(20), getFundRankings(20)])
    sectors.value = s || []
    funds.value = f || []
  } catch { /* silent */ } finally {
    loading.value = false
  }
})

function fmtPct(val: number) {
  const sign = val > 0 ? '+' : ''
  return sign + val.toFixed(2) + '%'
}

function pctClass(val: number) {
  if (val > 0) return 'up'
  if (val < 0) return 'down'
  return 'flat'
}

function goFund(code: string) { router.push('/fund/' + code) }
function goBack() { router.back() }
</script>

<template>
  <div class="page">
    <div class="detail-header">
      <button class="back-btn" @click="goBack">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5m7-7l-7 7 7 7"/></svg>
      </button>
      <div class="header-title">板块涨跌</div>
    </div>

    <div class="tab-bar">
      <button class="tab-btn" :class="{ active: tab === 'sector' }" @click="tab = 'sector'">板块排行</button>
      <button class="tab-btn" :class="{ active: tab === 'fund' }" @click="tab = 'fund'">基金排行</button>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <template v-else>
      <div v-if="tab === 'sector'" class="ranking-list">
        <div v-for="(s, i) in sectors" :key="s.sectorCode" class="ranking-item">
          <span class="rank-no" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span>
          <span class="rank-name">{{ s.sectorName }}</span>
          <span class="rank-return" :class="pctClass(s.avgReturnPct)">{{ fmtPct(s.avgReturnPct) }}</span>
        </div>
      </div>

      <div v-else class="ranking-list">
        <div v-for="(f, i) in funds" :key="f.fundCode" class="ranking-item clickable" @click="goFund(f.fundCode)">
          <span class="rank-no" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span>
          <div class="rank-info">
            <div class="rank-name">{{ f.fundName }}</div>
            <div class="rank-code">{{ f.fundCode }}</div>
          </div>
          <div class="rank-right">
            <span class="rank-nav">{{ f.unitNav?.toFixed(4) || '--' }}</span>
            <span class="rank-return" :class="pctClass(f.estimateReturnPct || 0)">{{ fmtPct(f.estimateReturnPct || 0) }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.detail-header {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px; background: var(--color-bg-card);
  border-bottom: 1px solid var(--color-border);
}
.back-btn {
  width: 36px; height: 36px; border: none; border-radius: 8px;
  background: transparent; color: var(--color-text-secondary);
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.header-title { font-size: 17px; font-weight: 700; color: var(--color-text-primary); }
.tab-bar {
  display: flex; gap: 4px; padding: 8px 16px;
}
.tab-btn {
  flex: 1; border: none; border-radius: 8px; padding: 8px 0;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.tab-btn.active { background: var(--color-primary); color: #fff; }
.loading-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
.ranking-list { background: var(--color-bg-card); margin: 0 16px; border-radius: 10px; overflow: hidden; }
.ranking-item {
  display: flex; align-items: center; padding: 12px 14px; gap: 10px;
  border-bottom: 1px solid var(--color-border);
}
.ranking-item.clickable { cursor: pointer; }
.ranking-item.clickable:hover { background: var(--color-bg-hover); }
.ranking-item:last-child { border-bottom: none; }
.rank-no {
  width: 22px; height: 22px; border-radius: 6px; font-size: 12px; font-weight: 800;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
}
.rank-no.top { background: #FEF3C7; color: #D97706; }
.rank-name { flex: 1; font-size: 14px; font-weight: 600; color: var(--color-text-primary); }
.rank-code { font-size: 11px; color: var(--color-text-secondary); }
.rank-return { font-size: 14px; font-weight: 700; }
.rank-return.up { color: var(--color-up); }
.rank-return.down { color: var(--color-down); }
.rank-return.flat { color: var(--color-text-secondary); }
.rank-info { flex: 1; min-width: 0; }
.rank-right { text-align: right; }
.rank-nav { font-size: 13px; color: var(--color-text-primary); display: block; }
</style>
