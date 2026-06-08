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
function fmtPrice(val: number | null | undefined) { if (val == null) return '--'; return val.toFixed(2) }
function goFund(code: string) { router.push('/fund/' + code) }
function goMarket(symbol: string) { router.push('/market/' + symbol) }
function goSectorRankings() { router.push('/sector-rankings') }
function sectorBarWidth(pct: number | null | undefined) { if (!pct) return '0%'; return Math.min(Math.abs(pct) * 12, 100) + '%' }
</script>

<template>
  <div class="page">
    <div v-if="loading" class="grid-skel">
      <div class="skel-cards"><div class="skel-card" v-for="i in 4" :key="i"></div></div>
      <div class="skel-body"><div class="skel-block"></div><div class="skel-side"></div></div>
    </div>

    <template v-else>
      <!-- Index cards -->
      <div class="index-section">
        <div class="index-scroll">
          <div v-for="idx in overview?.indices" :key="idx.symbol" class="index-card" :class="pctCls(idx.changePct)" @click="goMarket(idx.symbol)">
            <div class="idx-top">
              <span class="idx-name">{{ idx.name }}</span>
              <span class="idx-market">{{ idx.market }}</span>
            </div>
            <div class="idx-price">{{ fmtPrice(idx.lastPrice) }}</div>
            <div class="idx-row">
              <span class="idx-change">{{ (idx.changeAmount > 0 ? '+' : '') + (idx.changeAmount?.toFixed(2) || '0.00') }}</span>
              <span class="idx-pct">{{ fmtPct(idx.changePct) }}</span>
            </div>
            <div class="idx-bar"><div class="idx-bar-fill" :class="pctCls(idx.changePct)" :style="'width:' + Math.min(Math.abs(idx.changePct || 0) * 8, 100) + '%'"></div></div>
          </div>
        </div>
        <!-- Breadth -->
        <div class="breadth-row" v-if="overview?.upCount != null || overview?.riseCount != null">
          <div class="breadth-label">涨跌分布</div>
          <div class="breadth-bar-wrap">
            <div class="breadth-bar-up" :style="'width:' + ((overview.upCount ?? overview.riseCount ?? 0) / ((overview.upCount ?? overview.riseCount ?? 0) + (overview.downCount ?? overview.fallCount ?? 1)) * 100) + '%'"></div>
          </div>
          <div class="breadth-nums">
            <span class="br-up">{{ overview.upCount ?? overview.riseCount }} 涨</span>
            <span class="br-down">{{ overview.downCount ?? overview.fallCount }} 跌</span>
          </div>
        </div>
      </div>

      <!-- Main content -->
      <div class="main-grid">
        <!-- Fund rankings -->
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">基金涨幅榜</span>
            <span class="panel-sub">实时估值</span>
          </div>
          <div class="fund-list">
            <div v-for="(fund, i) in rankings" :key="fund.fundCode" class="fund-row" @click="goFund(fund.fundCode)">
              <div class="fr-rank" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</div>
              <div class="fr-info">
                <div class="fr-name">{{ fund.fundName }}</div>
                <div class="fr-meta">{{ fund.fundCode }}<span v-if="fund.sectorName" class="fr-sector">{{ fund.sectorName }}</span></div>
              </div>
              <div class="fr-nav">{{ fund.latestUnitNav?.toFixed(4) || '--' }}</div>
              <div class="fr-return" :class="pctCls(fund.returnPct)">
                <span class="fr-pct-val">{{ fmtPct(fund.returnPct) }}</span>
                <div class="fr-pct-bar"><div class="fr-pct-fill" :class="pctCls(fund.returnPct)" :style="'width:' + Math.min(Math.abs(fund.returnPct || 0) * 10, 100) + '%'"></div></div>
              </div>
            </div>
          </div>
        </div>

        <!-- Sectors sidebar -->
        <div class="side-col">
          <div class="panel">
            <div class="panel-head">
              <span class="panel-title">板块涨幅</span>
              <button class="panel-more" @click="goSectorRankings">更多</button>
            </div>
            <div class="sector-list">
              <div v-for="(sec, i) in sectors" :key="sec.code" class="sector-item" :class="pctCls(sec.changePct)">
                <div class="si-rank">{{ i + 1 }}</div>
                <div class="si-info">
                  <div class="si-name">{{ sec.name }}</div>
                  <div class="si-bar"><div class="si-bar-fill" :class="pctCls(sec.changePct)" :style="'width:' + sectorBarWidth(sec.changePct)"></div></div>
                </div>
                <div class="si-pct" :class="pctCls(sec.changePct)">{{ fmtPct(sec.changePct) }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.index-section { margin-bottom: 20px; }
.index-scroll { display: flex; gap: 12px; overflow-x: auto; padding-bottom: 4px; }
.index-scroll::-webkit-scrollbar { display: none; }

.index-card {
  flex: 0 0 180px; padding: 14px 16px 10px; border-radius: 10px;
  background: var(--color-bg-card); box-shadow: 0 2px 8px var(--color-shadow);
  cursor: pointer; transition: all 0.2s; position: relative;
}
.index-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.12); transform: translateY(-2px); }
.index-card.up { border-top: 3px solid var(--color-up); }
.index-card.down { border-top: 3px solid var(--color-down); }
.index-card.flat { border-top: 3px solid var(--color-border); }

.idx-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.idx-name { font-size: 13px; font-weight: 700; color: var(--color-text-primary); }
.idx-market { font-size: 10px; color: var(--color-text-tertiary); padding: 1px 5px; background: var(--color-bg-secondary); border-radius: 3px; }
.idx-price { font-size: 22px; font-weight: 900; font-variant-numeric: tabular-nums; letter-spacing: -0.5px; margin-bottom: 4px; }
.index-card.up .idx-price { color: var(--color-up); }
.index-card.down .idx-price { color: var(--color-down); }
.index-card.flat .idx-price { color: var(--color-text-primary); }
.idx-row { display: flex; justify-content: space-between; align-items: center; }
.idx-change { font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; }
.idx-pct { font-size: 13px; font-weight: 800; font-variant-numeric: tabular-nums; padding: 2px 6px; border-radius: 4px; }
.index-card.up .idx-change, .index-card.up .idx-pct { color: var(--color-up); }
.index-card.up .idx-pct { background: var(--color-up-bg); }
.index-card.down .idx-change, .index-card.down .idx-pct { color: var(--color-down); }
.index-card.down .idx-pct { background: var(--color-down-bg); }
.index-card.flat .idx-change, .index-card.flat .idx-pct { color: var(--color-text-secondary); }

.idx-bar { margin-top: 8px; height: 3px; background: var(--color-bg-secondary); border-radius: 2px; overflow: hidden; }
.idx-bar-fill { height: 100%; border-radius: 2px; transition: width 0.5s; }
.idx-bar-fill.up { background: var(--color-up); }
.idx-bar-fill.down { background: var(--color-down); }
.idx-bar-fill.flat { background: var(--color-border); }

/* Breadth */
.breadth-row {
  display: flex; align-items: center; gap: 12px; margin-top: 14px;
  padding: 12px 16px; background: var(--color-bg-card); border-radius: 10px;
  box-shadow: 0 1px 4px var(--color-shadow);
}
.breadth-label { font-size: 12px; color: var(--color-text-tertiary); font-weight: 600; white-space: nowrap; }
.breadth-bar-wrap { flex: 1; height: 8px; background: var(--color-down-bg); border-radius: 4px; overflow: hidden; }
.breadth-bar-up { height: 100%; background: var(--color-up-bg); border-radius: 4px; transition: width 0.5s; }
.breadth-nums { display: flex; gap: 12px; font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums; }
.br-up { color: var(--color-up); }
.br-down { color: var(--color-down); }

/* Main grid */
.main-grid { display: grid; grid-template-columns: 1fr 300px; gap: 16px; }
.panel { background: var(--color-bg-card); border-radius: 10px; box-shadow: 0 2px 8px var(--color-shadow); overflow: hidden; }
.panel-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; border-bottom: 1px solid var(--color-divider); }
.panel-title { font-size: 14px; font-weight: 800; color: var(--color-text-primary); }
.panel-sub { font-size: 11px; color: var(--color-text-tertiary); background: var(--color-bg-secondary); padding: 2px 6px; border-radius: 3px; }
.panel-more { border: none; background: none; color: var(--color-primary); font-size: 12px; font-weight: 600; }
.panel-more:hover { text-decoration: underline; }

/* Fund list */
.fund-list { }
.fund-row {
  display: flex; align-items: center; gap: 10px; padding: 11px 16px;
  border-bottom: 1px solid var(--color-divider); cursor: pointer; transition: background 0.1s;
}
.fund-row:last-child { border-bottom: none; }
.fund-row:hover { background: var(--color-primary-light); }

.fr-rank {
  width: 24px; height: 24px; line-height: 24px; text-align: center;
  border-radius: 6px; font-size: 11px; font-weight: 900;
  background: var(--color-bg-secondary); color: var(--color-text-tertiary); flex-shrink: 0;
}
.fr-rank.top { background: linear-gradient(135deg, #FEF3C7, #FDE68A); color: #92400E; }

.fr-info { flex: 1; min-width: 0; }
.fr-name { font-size: 13px; font-weight: 600; color: var(--color-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fr-meta { font-size: 11px; color: var(--color-text-tertiary); margin-top: 2px; display: flex; align-items: center; gap: 6px; }
.fr-sector { padding: 0 4px; border: 1px solid var(--color-border); border-radius: 3px; font-size: 10px; }

.fr-nav { font-size: 13px; font-weight: 600; color: var(--color-text-primary); text-align: right; min-width: 70px; font-variant-numeric: tabular-nums; }

.fr-return { text-align: right; min-width: 90px; }
.fr-pct-val { font-size: 14px; font-weight: 800; font-variant-numeric: tabular-nums; }
.fr-pct-val.up { color: var(--color-up); }
.fr-pct-val.down { color: var(--color-down); }
.fr-pct-val.flat { color: var(--color-text-secondary); }
.fr-pct-bar { margin-top: 3px; height: 3px; background: var(--color-bg-secondary); border-radius: 2px; overflow: hidden; }
.fr-pct-fill { height: 100%; border-radius: 2px; transition: width 0.5s; }
.fr-pct-fill.up { background: var(--color-up); }
.fr-pct-fill.down { background: var(--color-down); }

/* Sector sidebar */
.side-col { }
.sector-list { }
.sector-item {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  border-bottom: 1px solid var(--color-divider); transition: background 0.1s;
}
.sector-item:last-child { border-bottom: none; }
.sector-item:hover { background: var(--color-primary-light); }

.si-rank { font-size: 11px; font-weight: 700; color: var(--color-text-tertiary); width: 16px; text-align: center; flex-shrink: 0; }
.si-info { flex: 1; min-width: 0; }
.si-name { font-size: 13px; font-weight: 600; color: var(--color-text-primary); margin-bottom: 4px; }
.si-bar { height: 4px; background: var(--color-bg-secondary); border-radius: 2px; overflow: hidden; }
.si-bar-fill { height: 100%; border-radius: 2px; transition: width 0.5s; }
.si-bar-fill.up { background: linear-gradient(90deg, var(--color-up), #F87171); }
.si-bar-fill.down { background: linear-gradient(90deg, var(--color-down), #86EFAC); }
.si-bar-fill.flat { background: var(--color-border); }

.si-pct { font-size: 14px; font-weight: 800; font-variant-numeric: tabular-nums; min-width: 60px; text-align: right; flex-shrink: 0; }
.si-pct.up { color: var(--color-up); }
.si-pct.down { color: var(--color-down); }
.si-pct.flat { color: var(--color-text-secondary); }

/* Skeleton */
.grid-skel { }
.skel-cards { display: flex; gap: 12px; margin-bottom: 16px; }
.skel-card { flex: 0 0 180px; height: 100px; border-radius: 10px; background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
.skel-body { display: grid; grid-template-columns: 1fr 300px; gap: 16px; }
.skel-block { height: 400px; border-radius: 10px; background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
.skel-side { height: 300px; border-radius: 10px; background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>