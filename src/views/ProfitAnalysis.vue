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

function goBack() { router.back() }

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="detail-header">
      <button class="back-btn" @click="goBack">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5m7-7l-7 7 7 7"/></svg>
      </button>
      <div class="header-title">盈亏分析</div>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <template v-else>
      <div class="profit-summary" v-if="data">
        <div class="profit-item">
          <div class="profit-label">累计收益</div>
          <div class="profit-value" :class="(data.totalProfit || 0) >= 0 ? 'up' : 'down'">
            {{ data.totalProfit?.toFixed(2) || '0.00' }}
          </div>
        </div>
        <div class="profit-item">
          <div class="profit-label">收益率</div>
          <div class="profit-value" :class="(data.totalProfitPct || 0) >= 0 ? 'up' : 'down'">
            {{ (data.totalProfitPct > 0 ? '+' : '') + (data.totalProfitPct?.toFixed(2) || '0.00') }}%
          </div>
        </div>
      </div>

      <div class="chart-section">
        <div class="range-tabs">
          <button v-for="r in ranges" :key="r.key" class="range-tab" :class="{ active: currentRange === r.key }" @click="changeRange(r.key)">{{ r.label }}</button>
        </div>
        <LineChart
          v-if="data?.dailyData?.length"
          :points="data.dailyData.map((d: any) => ({ date: d.date, value: d.profit }))"
          :height="200"
          :color="(data.totalProfit || 0) >= 0 ? '#16A34A' : '#DC2626'"
        />
        <div v-else class="empty-state">暂无数据</div>
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
.loading-state, .empty-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
.profit-summary {
  display: flex; gap: 12px; padding: 16px;
}
.profit-item {
  flex: 1; padding: 14px; border-radius: 10px;
  background: var(--color-bg-card); text-align: center;
}
.profit-label { font-size: 12px; color: var(--color-text-secondary); }
.profit-value { font-size: 20px; font-weight: 800; margin-top: 4px; }
.profit-value.up { color: var(--color-up); }
.profit-value.down { color: var(--color-down); }
.chart-section { padding: 0 16px; }
.range-tabs {
  display: flex; gap: 4px; padding: 4px; margin-bottom: 8px;
  background: var(--color-bg-secondary); border-radius: 8px;
}
.range-tab {
  flex: 1; border: none; border-radius: 6px; padding: 6px 0;
  background: transparent; color: var(--color-text-secondary);
  font-size: 12px; cursor: pointer;
}
.range-tab.active { background: var(--color-bg-card); color: var(--color-primary); font-weight: 600; }
</style>
