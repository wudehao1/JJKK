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
function goFund(code: string) { router.push('/fund/' + code) }
</script>

<template>
  <div class="page">
    <div class="tab-bar">
      <button class="tab-btn" :class="{ active: tab === 'sector' }" @click="tab = 'sector'">板块排行</button>
      <button class="tab-btn" :class="{ active: tab === 'fund' }" @click="tab = 'fund'">基金排行</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>

    <template v-else>
      <!-- Sector table -->
      <div class="panel" v-if="tab === 'sector'">
        <table class="data-table">
          <thead><tr><th class="col-no">#</th><th class="col-name">板块名称</th><th class="col-num">最新价</th><th class="col-num">涨跌幅</th><th class="col-num">主力净流入</th></tr></thead>
          <tbody>
            <tr v-for="(s, i) in sectors" :key="s.code">
              <td class="col-no"><span class="rank-badge" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span></td>
              <td class="col-name">{{ s.name }}</td>
              <td class="col-num">{{ s.latestValue?.toFixed(2) || '--' }}</td>
              <td class="col-num" :class="pctCls(s.changePct)">{{ fmtPct(s.changePct) }}</td>
              <td class="col-num" :class="s.mainNetInflow > 0 ? 'up' : s.mainNetInflow < 0 ? 'down' : 'flat'">{{ s.mainNetInflow != null ? (s.mainNetInflow / 100000000).toFixed(2) + '亿' : '--' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Fund table -->
      <div class="panel" v-else>
        <table class="data-table">
          <thead><tr><th class="col-no">#</th><th class="col-name">基金名称</th><th class="col-code">代码</th><th class="col-sm">板块</th><th class="col-num">净值</th><th class="col-num">涨跌幅</th><th class="col-sm">数据</th></tr></thead>
          <tbody>
            <tr v-for="(f, i) in funds" :key="f.fundCode" @click="goFund(f.fundCode)">
              <td class="col-no"><span class="rank-badge" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span></td>
              <td class="col-name">{{ f.fundName }}</td>
              <td class="col-code">{{ f.fundCode }}</td>
              <td class="col-sm">{{ f.sectorName || '--' }}</td>
              <td class="col-num">{{ f.latestUnitNav?.toFixed(4) || '--' }}</td>
              <td class="col-num" :class="pctCls(f.returnPct)">{{ fmtPct(f.returnPct) }}</td>
              <td class="col-sm"><span class="type-tag" :class="f.dataType === 'ESTIMATE' ? 'est' : 'off'">{{ f.dataType === 'ESTIMATE' ? '估算' : '官方' }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tab-bar { display: flex; gap: 4px; margin-bottom: 12px; }
.tab-btn { border: none; border-radius: var(--radius); padding: 8px 20px; background: var(--color-bg-secondary); color: var(--color-text-secondary); font-size: 13px; font-weight: 600; }
.tab-btn.active { background: var(--color-primary); color: #fff; }
.loading { text-align: center; padding: 40px; color: var(--color-text-tertiary); }

.panel { background: var(--color-bg-card); border-radius: var(--radius-lg); box-shadow: 0 2px 8px var(--color-shadow); overflow: hidden; }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { padding: 8px 12px; text-align: left; font-size: 11px; font-weight: 600; color: var(--color-text-tertiary); border-bottom: 1px solid var(--color-divider); text-transform: uppercase; letter-spacing: 0.3px; }
.data-table td { padding: 10px 12px; border-bottom: 1px solid var(--color-divider); font-size: 13px; }
.data-table tr { transition: background 0.1s; }
.data-table tr:hover { background: var(--color-primary-light); }
.data-table tr:last-child td { border-bottom: none; }
.col-no { width: 40px; text-align: center; }
.col-name { font-weight: 600; color: var(--color-text-primary); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.col-code { color: var(--color-text-tertiary); font-size: 12px; }
.col-num { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; }
.col-sm { font-size: 12px; color: var(--color-text-tertiary); } .col-bar { width: 100px; } .pct-bar { height: 6px; border-radius: 3px; transition: width 0.3s; } .pct-bar.up { background: linear-gradient(90deg, var(--color-up), #F87171); } .pct-bar.down { background: linear-gradient(90deg, var(--color-down), #86EFAC); } .pct-bar.flat { background: var(--color-bg-secondary); }
td.up { color: var(--color-up); }
td.down { color: var(--color-down); }
td.flat { color: var(--color-text-secondary); }
.rank-badge { display: inline-block; width: 20px; height: 20px; line-height: 20px; text-align: center; border-radius: 4px; font-size: 11px; font-weight: 800; background: var(--color-bg-secondary); color: var(--color-text-tertiary); }
.rank-badge.top { background: linear-gradient(135deg, #FEF3C7, #FDE68A); color: #B45309; box-shadow: 0 1px 3px rgba(180,83,9,0.15); }
.type-tag { display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 10px; font-weight: 600; }
.type-tag.est { background: var(--color-primary-light); color: var(--color-primary); }
.type-tag.off { background: var(--color-bg-secondary); color: var(--color-text-tertiary); }
</style>
