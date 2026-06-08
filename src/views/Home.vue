<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getMarketOverview, getMarketMinute } from '@/api/market'
import LineChart from '@/components/LineChart.vue'
import type { MarketOverview, IndexQuote } from '@/types'

const router = useRouter()
const overview = ref<MarketOverview | null>(null)
const loading = ref(true)
const activeTab = ref('A股')
const minuteCache = ref<Record<string, { date: string; value: number }[]>>({})
const minuteLoading = ref<Record<string, boolean>>({})

const tabs = [
  { key: 'A股', label: 'A股' },
  { key: '港股', label: '港股' },
  { key: '美股', label: '美股' },
  { key: '黄金', label: '黄金' },
]

function marketGroup(market: string): string {
  if (market === 'SSE' || market === 'SZSE') return 'A股'
  if (market === 'HKEX') return '港股'
  if (market === 'NYSE' || market === 'NASDAQ') return '美股'
  return '黄金'
}

const filteredIndices = computed(() => {
  const indices = overview.value?.indices || []
  return indices.filter(idx => marketGroup(idx.market) === activeTab.value && idx.lastPrice != null)
})

onMounted(async () => {
  try {
    overview.value = await getMarketOverview()
  } catch {} finally {
    loading.value = false
  }
  loadMinuteForTab(activeTab.value)
})

watch(activeTab, (tab) => { loadMinuteForTab(tab) })

async function loadMinuteForTab(tab: string) {
  const indices = (overview.value?.indices || []).filter(idx => marketGroup(idx.market) === tab && idx.lastPrice != null)
  for (const idx of indices) {
    if (minuteCache.value[idx.symbol] || minuteLoading.value[idx.symbol]) continue
    minuteLoading.value[idx.symbol] = true
    try {
      const data: any = await getMarketMinute(idx.symbol)
      const pts = data?.points || []
      minuteCache.value[idx.symbol] = pts.map((p: any) => ({
        date: p.quoteTime || p.time || p.date || '',
        value: p.price || p.close || p.value || 0
      }))
    } catch {
      minuteCache.value[idx.symbol] = []
    } finally {
      minuteLoading.value[idx.symbol] = false
    }
  }
}

function fmtPct(val: number | null | undefined) {
  if (val == null) return '--'
  return (val > 0 ? '+' : '') + val.toFixed(2) + '%'
}
function pctCls(val: number | null | undefined) {
  if (val == null) return 'flat'
  return val > 0 ? 'up' : val < 0 ? 'down' : 'flat'
}
function fmtPrice(val: number | null | undefined) {
  if (val == null) return '--'
  return val.toFixed(2)
}
function goMarket(symbol: string) { router.push('/market/' + symbol) }
function chartColor(pct: number | null | undefined) {
  if (pct == null || pct === 0) return undefined
  return pct > 0 ? 'var(--color-up)' : 'var(--color-down)'
}
</script>

<template>
  <div class="page">
    <!-- Tabs -->
    <div class="market-tabs">
      <button v-for="t in tabs" :key="t.key" class="mtab" :class="{ active: activeTab === t.key }" @click="activeTab = t.key">{{ t.label }}</button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="skel-grid">
      <div class="skel-card" v-for="i in 3" :key="i"></div>
    </div>

    <!-- Index cards grid -->
    <div v-else class="index-grid">
      <div v-for="idx in filteredIndices" :key="idx.symbol" class="index-card" :class="pctCls(idx.changePct)" @click="goMarket(idx.symbol)">
        <div class="ic-header">
          <span class="ic-name">{{ idx.name }}</span>
          <span class="ic-tag">{{ marketGroup(idx.market) }}</span>
        </div>
        <div class="ic-body">
          <div class="ic-left">
            <div class="ic-price" :class="pctCls(idx.changePct)">{{ fmtPrice(idx.lastPrice) }}</div>
            <div class="ic-change">
              <span class="ic-amt">{{ (idx.changeAmount > 0 ? '+' : '') + (idx.changeAmount?.toFixed(2) || '0.00') }}</span>
              <span class="ic-pct" :class="pctCls(idx.changePct)">{{ fmtPct(idx.changePct) }}</span>
            </div>
          </div>
          <div class="ic-chart">
            <div v-if="minuteLoading[idx.symbol]" class="ic-chart-skel"></div>
            <LineChart v-else-if="minuteCache[idx.symbol]?.length" :points="minuteCache[idx.symbol]!" :height="60" :color="chartColor(idx.changePct)" :showGrid="false" />
          </div>
        </div>
      </div>
      <div v-if="!filteredIndices.length" class="empty-hint">该分组暂无数据</div>
    </div>
  </div>
</template>

<style scoped>
.market-tabs {
  display: flex; gap: 4px; margin-bottom: 20px;
  background: var(--color-bg-card); border-radius: 10px;
  padding: 4px; box-shadow: 0 1px 4px var(--color-shadow);
  width: fit-content;
}
.mtab {
  border: none; border-radius: 8px; padding: 8px 24px;
  background: transparent; color: var(--color-text-secondary);
  font-size: 14px; font-weight: 600; transition: all 0.2s;
}
.mtab:hover { background: var(--color-bg-secondary); }
.mtab.active { background: var(--color-primary); color: #fff; box-shadow: 0 2px 6px rgba(37,99,235,0.3); }

.index-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px;
}

.index-card {
  background: var(--color-bg-card); border-radius: 12px;
  box-shadow: 0 2px 8px var(--color-shadow); overflow: hidden;
  cursor: pointer; transition: all 0.2s;
  border-left: 4px solid transparent;
}
.index-card:hover { box-shadow: 0 6px 20px rgba(0,0,0,0.12); transform: translateY(-2px); }
.index-card.up { border-left-color: var(--color-up); }
.index-card.down { border-left-color: var(--color-down); }
.index-card.flat { border-left-color: var(--color-border); }

.ic-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 16px 0;
}
.ic-name { font-size: 15px; font-weight: 800; color: var(--color-text-primary); }
.ic-tag {
  font-size: 10px; color: var(--color-text-tertiary); font-weight: 600;
  padding: 2px 6px; background: var(--color-bg-secondary); border-radius: 4px;
}

.ic-body { display: flex; align-items: flex-end; padding: 8px 16px 14px; gap: 12px; }

.ic-left { flex: 0 0 auto; min-width: 120px; }
.ic-price { font-size: 26px; font-weight: 900; font-variant-numeric: tabular-nums; letter-spacing: -0.5px; line-height: 1.1; }
.ic-price.up { color: var(--color-up); }
.ic-price.down { color: var(--color-down); }
.ic-price.flat { color: var(--color-text-primary); }

.ic-change { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.ic-amt { font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; color: var(--color-text-secondary); }
.ic-pct {
  font-size: 14px; font-weight: 800; font-variant-numeric: tabular-nums;
  padding: 2px 8px; border-radius: 4px;
}
.ic-pct.up { color: var(--color-up); background: var(--color-up-bg); }
.ic-pct.down { color: var(--color-down); background: var(--color-down-bg); }
.ic-pct.flat { color: var(--color-text-secondary); background: var(--color-bg-secondary); }

.ic-chart { flex: 1; min-width: 0; height: 60px; }
.ic-chart-skel {
  width: 100%; height: 100%; border-radius: 6px;
  background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%);
  background-size: 200% 100%; animation: shimmer 1.5s infinite;
}

.empty-hint { grid-column: 1 / -1; text-align: center; padding: 40px; color: var(--color-text-tertiary); font-size: 13px; }

.skel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px; }
.skel-card {
  height: 130px; border-radius: 12px;
  background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%);
  background-size: 200% 100%; animation: shimmer 1.5s infinite;
}
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>