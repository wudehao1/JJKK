<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMarketOverview, getSectorRankings, getFundRankings, getMarketFundBreadth } from '@/api/market'
import type { MarketOverview, SectorRanking, FundRanking } from '@/types'

const router = useRouter()
const overview = ref<MarketOverview | null>(null)
const sectors = ref<SectorRanking[]>([])
const rankings = ref<FundRanking[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const [ov, sec, rank, breadth] = await Promise.all([getMarketOverview(), getSectorRankings(12), getFundRankings(20), getMarketFundBreadth().catch(() => null)])
    if (breadth && typeof breadth === 'object') { const b = breadth as any; ov.upCount = b.upCount ?? b.riseCount; ov.downCount = b.downCount ?? b.fallCount }
    overview.value = ov; sectors.value = sec || []; rankings.value = rank || []
  } catch {} finally { loading.value = false }
})

function fmtPct(val: number | null | undefined) { if (val == null) return '--'; return (val > 0 ? '+' : '') + val.toFixed(2) + '%' }
function pctCls(val: number | null | undefined) { if (val == null) return 'flat'; return val > 0 ? 'up' : val < 0 ? 'down' : 'flat' }
function goFund(code: string) { router.push('/fund/' + code) }
function goMarket(symbol: string) { router.push('/market/' + symbol) }
function goSectorRankings() { router.push('/sector-rankings') }
</script>

<template>
  <div class="page">
    <div v-if="loading" class="grid-skel"><div class="skel-card" v-for="i in 3" :key="i"></div><div class="skel-block"></div><div class="skel-block"></div></div>

    <template v-else>
      <!-- Index cards -->
      <div class="index-row" v-if="overview?.indices?.length">
        <div v-for="idx in overview.indices" :key="idx.symbol" class="index-card" :class="pctCls(idx.changePct)" @click="goMarket(idx.symbol)">
          <div class="idx-name">{{ idx.name }}</div>
          <div class="idx-price">{{ idx.lastPrice?.toFixed(2) || '--' }}</div>
          <div class="idx-change">
            <span>{{ (idx.changeAmount > 0 ? '+' : '') + (idx.changeAmount?.toFixed(2) || '0.00') }}</span>
            <span class="idx-pct">{{ fmtPct(idx.changePct) }}</span>
          </div>
        </div>
        <!-- Breadth -->
        <div class="breadth-card" v-if="overview.upCount != null || overview.riseCount != null">
          <div class="br-item"><span class="br-dot up"></span><span class="br-label">上涨</span><span class="br-val up">{{ overview.upCount ?? overview.riseCount }}</span></div>
          <div class="br-sep"></div>
          <div class="br-item"><span class="br-dot down"></span><span class="br-label">下跌</span><span class="br-val down">{{ overview.downCount ?? overview.fallCount }}</span></div>
        </div>
      </div>

      <!-- Main grid -->
      <div class="main-grid">
        <!-- Fund rankings table -->
        <div class="panel">
          <div class="panel-head"><span class="panel-title">基金涨幅榜</span></div>
          <table class="data-table">
            <thead><tr><th class="col-no">#</th><th class="col-name">名称</th><th class="col-code">代码</th><th class="col-num">净值</th><th class="col-num">涨跌幅</th></tr></thead>
            <tbody>
              <tr v-for="(fund, i) in rankings" :key="fund.fundCode" @click="goFund(fund.fundCode)">
                <td class="col-no"><span class="rank-badge" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span></td>
                <td class="col-name" :title="fund.fundName">{{ fund.fundName }}</td>
                <td class="col-code">{{ fund.fundCode }}</td>
                <td class="col-num">{{ fund.latestUnitNav?.toFixed(4) || '--' }}</td>
                <td class="col-num" :class="pctCls(fund.returnPct)">{{ fmtPct(fund.returnPct) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Sector rankings -->
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">板块涨幅</span>
            <button class="panel-more" @click="goSectorRankings">更多</button>
          </div>
          <div class="sector-list">
            <div v-for="sec in sectors" :key="sec.code" class="sector-row">
              <span class="sector-name">{{ sec.name }}</span>
              <span class="sector-pct" :class="pctCls(sec.changePct)">{{ fmtPct(sec.changePct) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.index-row { display: flex; gap: 12px; margin-bottom: 16px; }
.index-card {
  flex: 1; padding: 16px 18px; border-radius: var(--radius-lg);
  background: var(--color-bg-card); box-shadow: 0 2px 8px var(--color-shadow);
  cursor: pointer; transition: all 0.2s; position: relative; overflow: hidden;
}
.index-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.1); transform: translateY(-1px); }
.index-card.up { border-left: 3px solid var(--color-up); background: linear-gradient(135deg, var(--color-bg-card) 70%, var(--color-up-bg) 100%); }
.index-card.down { border-left: 3px solid var(--color-down); background: linear-gradient(135deg, var(--color-bg-card) 70%, var(--color-down-bg) 100%); }
.index-card.flat { border-left: 3px solid var(--color-border); }
.idx-name { font-size: 12px; color: var(--color-text-tertiary); font-weight: 500; }
.idx-price { font-size: 24px; font-weight: 800; margin: 6px 0 3px; font-variant-numeric: tabular-nums; letter-spacing: -0.5px; }
.index-card.up .idx-price, .index-card.up .idx-change { color: var(--color-up); }
.index-card.down .idx-price, .index-card.down .idx-change { color: var(--color-down); }
.idx-change { font-size: 12px; font-weight: 600; display: flex; gap: 8px; font-variant-numeric: tabular-nums; }
.breadth-card {
  display: flex; align-items: center; gap: 20px; padding: 0 24px;
  border-radius: var(--radius-lg); background: var(--color-bg-card);
  box-shadow: 0 2px 8px var(--color-shadow);
}
.br-item { display: flex; align-items: center; gap: 6px; }
.br-dot { width: 6px; height: 6px; border-radius: 3px; }
.br-dot.up { background: var(--color-up); }
.br-dot.down { background: var(--color-down); }
.br-label { font-size: 12px; color: var(--color-text-tertiary); }
.br-val { font-size: 16px; font-weight: 800; font-variant-numeric: tabular-nums; }
.br-val.up { color: var(--color-up); }
.br-val.down { color: var(--color-down); }
.br-sep { width: 1px; height: 24px; background: var(--color-border); }

.main-grid { display: grid; grid-template-columns: 1fr 320px; gap: 16px; }
.panel { background: var(--color-bg-card); border-radius: var(--radius-lg); box-shadow: 0 2px 8px var(--color-shadow); overflow: hidden; }
.panel-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; border-bottom: 1px solid var(--color-divider); }
.panel-title { font-size: 14px; font-weight: 700; color: var(--color-text-primary); }
.panel-more { border: none; background: none; color: var(--color-text-tertiary); font-size: 12px; }
.panel-more:hover { color: var(--color-primary); }

.data-table { width: 100%; border-collapse: collapse; }
.data-table th { padding: 8px 12px; text-align: left; font-size: 11px; font-weight: 600; color: var(--color-text-tertiary); border-bottom: 1px solid var(--color-divider); text-transform: uppercase; letter-spacing: 0.3px; }
.data-table td { padding: 10px 12px; border-bottom: 1px solid var(--color-divider); font-size: 13px; }
.data-table tr { cursor: pointer; transition: background 0.1s; }
.data-table tr:hover { background: var(--color-primary-light); }
.data-table tr:last-child td { border-bottom: none; }
.col-no { width: 40px; text-align: center; }
.col-name { font-weight: 600; color: var(--color-text-primary); max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.col-code { color: var(--color-text-tertiary); font-size: 12px; }
.col-num { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; }
td.up { color: var(--color-up); }
td.down { color: var(--color-down); }
td.flat { color: var(--color-text-secondary); }
.rank-badge { display: inline-block; width: 22px; height: 22px; line-height: 22px; text-align: center; border-radius: 6px; font-size: 11px; font-weight: 800; background: var(--color-bg-secondary); color: var(--color-text-tertiary); }
.rank-badge.top { background: linear-gradient(135deg, #FEF3C7, #FDE68A); color: #B45309; box-shadow: 0 1px 3px rgba(180,83,9,0.15); }

.sector-list { padding: 4px 0; }
.sector-row { display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; border-bottom: 1px solid var(--color-divider); font-size: 13px; position: relative; }
.sector-row:last-child { border-bottom: none; }
.sector-name { color: var(--color-text-primary); font-weight: 500; }
.sector-pct { font-weight: 800; font-variant-numeric: tabular-nums; min-width: 64px; text-align: right; }
.sector-pct.up { color: var(--color-up); }
.sector-pct.down { color: var(--color-down); }
.sector-pct.flat { color: var(--color-text-secondary); }

.grid-skel { display: flex; gap: 12px; margin-bottom: 16px; }
.skel-card { flex: 1; height: 90px; border-radius: var(--radius-lg); background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
.skel-block { height: 300px; border-radius: var(--radius-lg); background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>
