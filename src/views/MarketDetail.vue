<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getMarketMinute, getMarketHistory } from '@/api/market'
import LineChart from '@/components/LineChart.vue'

const route = useRoute()
const router = useRouter()
const symbol = computed(() => route.params.symbol as string)
const currentRange = ref('today')
const points = ref<{ date: string; value: number }[]>([])
const loading = ref(true)

const ranges = [
  { key: 'today', label: '分时' },
  { key: '1w', label: '近1周' },
  { key: '1m', label: '近1月' },
  { key: '3m', label: '近3月' }
]

async function load() {
  loading.value = true
  try {
    if (currentRange.value === 'today') {
      const data: any = await getMarketMinute(symbol.value)
      const pts = Array.isArray(data) ? data : data?.points || []
        points.value = pts.map((p: any) => ({ date: p.quoteTime || p.time || p.date, value: p.price || p.close || p.value || 0 }))
    } else {
      const data: any = await getMarketHistory(symbol.value, currentRange.value)
      const pts = Array.isArray(data) ? data : data?.points || []
        points.value = pts.map((p: any) => ({ date: p.tradingDay || p.date, value: p.closePrice || p.close || p.nav || p.value || 0 }))
    }
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
      <span>{{ symbol }} 分时走势</span>
    </div>

    <div class="panel">
      <div class="panel-head">
        <div class="range-tabs">
          <button v-for="r in ranges" :key="r.key" class="range-tab" :class="{ active: currentRange === r.key }" @click="changeRange(r.key)">{{ r.label }}</button>
        </div>
      </div>
      <div class="chart-area">
        <div v-if="loading" class="loading-state">加载中...</div>
        <LineChart v-else :points="points" :height="320" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-text-tertiary); margin-bottom: 16px; }
.bc-link { cursor: pointer; color: var(--color-primary); }
.bc-sep { color: var(--color-border); }
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
.loading-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
</style>
