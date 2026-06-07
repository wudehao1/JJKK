<script setup lang="ts">
import { ref, onMounted } from 'vue'
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

import { computed } from 'vue'

async function load() {
  loading.value = true
  try {
    if (currentRange.value === 'today') {
      const data = await getMarketMinute(symbol.value)
      if (Array.isArray(data)) {
        points.value = data.map((p: any) => ({ date: p.time || p.date, value: p.price || p.value || 0 }))
      }
    } else {
      const data = await getMarketHistory(symbol.value, currentRange.value)
      if (Array.isArray(data)) {
        points.value = data.map((p: any) => ({ date: p.date, value: p.close || p.nav || p.value || 0 }))
      }
    }
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
      <div class="header-title">{{ symbol }} 分时走势</div>
    </div>

    <div class="chart-section">
      <div class="range-tabs">
        <button v-for="r in ranges" :key="r.key" class="range-tab" :class="{ active: currentRange === r.key }" @click="changeRange(r.key)">{{ r.label }}</button>
      </div>
      <div v-if="loading" class="loading-state">加载中...</div>
      <LineChart v-else :points="points" :height="240" />
    </div>
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
.chart-section { padding: 16px; }
.range-tabs {
  display: flex; gap: 4px; padding: 4px; margin-bottom: 12px;
  background: var(--color-bg-secondary); border-radius: 8px;
}
.range-tab {
  flex: 1; border: none; border-radius: 6px; padding: 6px 0;
  background: transparent; color: var(--color-text-secondary);
  font-size: 12px; cursor: pointer;
}
.range-tab.active { background: var(--color-bg-card); color: var(--color-primary); font-weight: 600; }
.loading-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
</style>
