<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getProfitAnalysis } from '@/api/user'
import LineChart from '@/components/LineChart.vue'

const router = useRouter()
const auth = useAuthStore()
const data = ref<any>(null)
const loading = ref(true)
const currentRange = ref('1m')

const ranges = [
  { key: '1w', label: '近1周' },
  { key: '1m', label: '近1月' },
  { key: '3m', label: '近3月' },
  { key: '6m', label: '近6月' },
  { key: '1y', label: '近1年' }
]

async function load() {
  loading.value = true
  try {
    data.value = await getProfitAnalysis(auth.userId, { range: currentRange.value })
  } catch { /* silent */ } finally {
    loading.value = false
  }
}

function changeRange(key: string) {
  currentRange.value = key
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="breadcrumb">
      <span class="bc-link" @click="router.push('/')">首页</span>
      <span class="bc-sep">/</span>
      <span>盈亏分析</span>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <template v-else>
      <div class="profit-summary" v-if="data">
        <div class="profit-item">
          <div class="profit-label">累计收益</div>
          <div class="profit-value" :class="(data.summary?.profitAmount || 0) >= 0 ? 'up' : 'down'">
            {{ data.summary?.profitAmount?.toFixed(2) || '0.00' }}
          </div>
        </div>
        <div class="profit-item">
          <div class="profit-label">收益率</div>
          <div class="profit-value" :class="(data.summary?.returnPct || 0) >= 0 ? 'up' : 'down'">
            {{ (data.summary?.returnPct > 0 ? '+' : '') + (data.summary?.returnPct?.toFixed(2) || '0.00') }}%
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <div class="range-tabs">
            <button v-for="r in ranges" :key="r.key" class="range-tab" :class="{ active: currentRange === r.key }" @click="changeRange(r.key)">{{ r.label }}</button>
          </div>
        </div>
        <div class="chart-area">
          <LineChart
            v-if="data?.trend?.length"
            :points="data.trend.map((d: any) => ({ date: d.date || d.key, value: d.profitAmount || 0 }))"
            :height="280"
            :color="(data.summary?.profitAmount || 0) >= 0 ? '#16A34A' : '#DC2626'"
          />
          <div v-else class="empty-state">暂无数据</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-text-tertiary); margin-bottom: 16px; }
.bc-link { cursor: pointer; color: var(--color-primary); }
.bc-sep { color: var(--color-border); }
.loading-state, .empty-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
.profit-summary {
  display: flex; gap: 12px; margin-bottom: 16px;
}
.profit-item {
  flex: 1; padding: 16px; border-radius: var(--radius-lg);
  background: var(--color-bg-card); text-align: center;
  box-shadow: 0 1px 3px var(--color-shadow);
}
.profit-label { font-size: 12px; color: var(--color-text-secondary); }
.profit-value { font-size: 22px; font-weight: 800; margin-top: 4px; }
.profit-value.up { color: var(--color-up); }
.profit-value.down { color: var(--color-down); }
.panel { background: var(--color-bg-card); border-radius: var(--radius-lg); box-shadow: 0 1px 3px var(--color-shadow); overflow: hidden; }
.panel-head { padding: 10px 14px; border-bottom: 1px solid var(--color-divider); }
.range-tabs {
  display: inline-flex; gap: 1px; padding: 2px; background: var(--color-bg-secondary); border-radius: 4px;
}
.range-tab {
  border: none; border-radius: 3px; padding: 5px 14px;
  background: transparent; color: var(--color-text-secondary);
  font-size: 12px; cursor: pointer;
}
.range-tab.active { background: var(--color-bg-card); color: var(--color-primary); font-weight: 600; box-shadow: 0 1px 2px var(--color-shadow); }
.chart-area { padding: 14px; }
</style>
