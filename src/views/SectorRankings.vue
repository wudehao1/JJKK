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
    const [s, f] = await Promise.all([getSectorRankings(30), getFundRankings(50)])
    sectors.value = s || []; funds.value = f || []
  } catch {} finally { loading.value = false }
})

function fmtPct(val: number | null | undefined) { if (val == null) return '--'; return (val > 0 ? '+' : '') + val.toFixed(2) + '%' }
function pctCls(val: number | null | undefined) { if (val == null) return 'flat'; return val > 0 ? 'up' : val < 0 ? 'down' : 'flat' }
function barWidth(pct: number | null | undefined) { if (!pct) return '0%'; return Math.min(Math.abs(pct) * 12, 100) + '%' }
function goFund(code: string) { router.push('/fund/' + code) }
</script>

<template>
  <div class="page">
    <div class="tab-bar">
      <button class="tab-btn" :class="{ active: tab === 'sector' }" @click="tab = 'sector'">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"/></svg>
        板块排行
      </button>
      <button class="tab-btn" :class="{ active: tab === 'fund' }" @click="tab = 'fund'">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 3v18h18"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/></svg>
        基金排行
      </button>
    </div>

    <div v-if="loading" class="loading">
      <div class="skel-item" v-for="i in 8" :key="i"></div>
    </div>

    <template v-else>
      <!-- Sector cards -->
      <div class="sector-grid" v-if="tab === 'sector'">
        <div v-for="(s, i) in sectors" :key="s.code" class="sector-card" :class="pctCls(s.changePct)">
          <div class="sc-header">
            <span class="sc-rank" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span>
            <span class="sc-name">{{ s.name }}</span>
          </div>
          <div class="sc-pct" :class="pctCls(s.changePct)">{{ fmtPct(s.changePct) }}</div>
          <div class="sc-bar"><div class="sc-bar-fill" :class="pctCls(s.changePct)" :style="'width:' + barWidth(s.changePct)"></div></div>
          <div class="sc-meta">
            <span class="sc-price">{{ s.latestValue?.toFixed(2) || '--' }}</span>
            <span class="sc-flow" :class="s.mainNetInflow > 0 ? 'up' : s.mainNetInflow < 0 ? 'down' : 'flat'">
              {{ s.mainNetInflow != null ? (s.mainNetInflow / 100000000).toFixed(2) + '亿' : '--' }}
            </span>
          </div>
        </div>
      </div>

      <!-- Fund table -->
      <div class="panel" v-else>
        <table class="data-table">
          <thead><tr><th class="col-no">#</th><th class="col-name">基金名称</th><th class="col-code">代码</th><th class="col-sm">板块</th><th class="col-num">净值</th><th class="col-num">涨跌幅</th><th class="col-bar">走势</th><th class="col-sm">数据</th></tr></thead>
          <tbody>
            <tr v-for="(f, i) in funds" :key="f.fundCode" @click="goFund(f.fundCode)">
              <td class="col-no"><span class="rank-badge" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span></td>
              <td class="col-name">{{ f.fundName }}</td>
              <td class="col-code">{{ f.fundCode }}</td>
              <td class="col-sm">{{ f.sectorName || '--' }}</td>
              <td class="col-num">{{ f.latestUnitNav?.toFixed(4) || '--' }}</td>
              <td class="col-num" :class="pctCls(f.returnPct)">{{ fmtPct(f.returnPct) }}</td>
              <td class="col-bar"><div class="pct-bar" :class="pctCls(f.returnPct)" :style="'width:' + barWidth(f.returnPct)"></div></td>
              <td class="col-sm"><span class="type-tag" :class="f.dataType === 'ESTIMATE' ? 'est' : 'off'">{{ f.dataType === 'ESTIMATE' ? '估算' : '官方' }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tab-bar { display: flex; gap: 4px; margin-bottom: 16px; }
.tab-btn {
  display: flex; align-items: center; gap: 6px;
  border: none; border-radius: 8px; padding: 10px 20px;
  background: var(--color-bg-card); color: var(--color-text-secondary);
  font-size: 13px; font-weight: 600; box-shadow: 0 1px 3px var(--color-shadow);
}
.tab-btn.active { background: var(--color-primary); color: #fff; }

.loading { }
.skel-item {
  height: 48px; border-radius: 8px; margin-bottom: 8px;
  background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%);
  background-size: 200% 100%; animation: shimmer 1.5s infinite;
}
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }

/* Sector cards */
.sector-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
.sector-card {
  padding: 12px 14px; border-radius: 10px; background: var(--color-bg-card);
  box-shadow: 0 1px 4px var(--color-shadow); transition: all 0.2s; cursor: default;
}
.sector-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.1); transform: translateY(-1px); }
.sector-card.up { border-left: 3px solid var(--color-up); }
.sector-card.down { border-left: 3px solid var(--color-down); }
.sector-card.flat { border-left: 3px solid var(--color-border); }

.sc-header { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.sc-rank {
  width: 18px; height: 18px; line-height: 18px; text-align: center;
  border-radius: 4px; font-size: 10px; font-weight: 800;
  background: var(--color-bg-secondary); color: var(--color-text-tertiary);
}
.sc-rank.top { background: linear-gradient(135deg, #FEF3C7, #FDE68A); color: #92400E; }
.sc-name { font-size: 13px; font-weight: 700; color: var(--color-text-primary); }

.sc-pct { font-size: 20px; font-weight: 900; font-variant-numeric: tabular-nums; margin-bottom: 6px; }
.sc-pct.up { color: var(--color-up); }
.sc-pct.down { color: var(--color-down); }
.sc-pct.flat { color: var(--color-text-secondary); }

.sc-bar { height: 4px; background: var(--color-bg-secondary); border-radius: 2px; overflow: hidden; margin-bottom: 8px; }
.sc-bar-fill { height: 100%; border-radius: 2px; transition: width 0.5s; }
.sc-bar-fill.up { background: linear-gradient(90deg, var(--color-up), #F87171); }
.sc-bar-fill.down { background: linear-gradient(90deg, var(--color-down), #86EFAC); }
.sc-bar-fill.flat { background: var(--color-border); }

.sc-meta { display: flex; justify-content: space-between; font-size: 11px; color: var(--color-text-tertiary); }
.sc-flow.up { color: var(--color-up); }
.sc-flow.down { color: var(--color-down); }

/* Fund table */
.panel { background: var(--color-bg-card); border-radius: 10px; box-shadow: 0 2px 8px var(--color-shadow); overflow: hidden; }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { padding: 10px 12px; text-align: left; font-size: 11px; font-weight: 600; color: var(--color-text-tertiary); border-bottom: 1px solid var(--color-divider); text-transform: uppercase; letter-spacing: 0.3px; }
.data-table td { padding: 11px 12px; border-bottom: 1px solid var(--color-divider); font-size: 13px; }
.data-table tr { transition: background 0.1s; cursor: pointer; }
.data-table tr:hover { background: var(--color-primary-light); }
.data-table tr:last-child td { border-bottom: none; }
.col-no { width: 40px; text-align: center; }
.col-name { font-weight: 600; color: var(--color-text-primary); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.col-code { color: var(--color-text-tertiary); font-size: 12px; }
.col-num { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; }
.col-sm { font-size: 12px; color: var(--color-text-tertiary); }
.col-bar { width: 80px; }
td.up { color: var(--color-up); }
td.down { color: var(--color-down); }
td.flat { color: var(--color-text-secondary); }

.rank-badge { display: inline-block; width: 22px; height: 22px; line-height: 22px; text-align: center; border-radius: 6px; font-size: 11px; font-weight: 800; background: var(--color-bg-secondary); color: var(--color-text-tertiary); }
.rank-badge.top { background: linear-gradient(135deg, #FEF3C7, #FDE68A); color: #92400E; }

.pct-bar { height: 6px; border-radius: 3px; transition: width 0.5s; }
.pct-bar.up { background: linear-gradient(90deg, var(--color-up), #F87171); }
.pct-bar.down { background: linear-gradient(90deg, var(--color-down), #86EFAC); }
.pct-bar.flat { background: var(--color-border); }

.type-tag { display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 10px; font-weight: 600; }
.type-tag.est { background: var(--color-primary-light); color: var(--color-primary); }
.type-tag.off { background: var(--color-bg-secondary); color: var(--color-text-tertiary); }
</style>