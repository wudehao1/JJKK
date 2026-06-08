<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps<{
  fundCode: string
  fundName: string
  unitNav?: number
  dailyReturnPct?: number
  estimateReturnPct?: number
  fundType?: string
  showEstimate?: boolean
}>()

const router = useRouter()
const returnVal = computed(() => props.estimateReturnPct ?? props.dailyReturnPct ?? 0)
const returnClass = computed(() => returnVal.value > 0 ? 'up' : returnVal.value < 0 ? 'down' : 'flat')

function fmtReturn(val?: number) {
  if (val === null || val === undefined) return '--'
  return (val > 0 ? '+' : '') + val.toFixed(2) + '%'
}
function fmtNav(val?: number) {
  if (val === null || val === undefined) return '--'
  return val.toFixed(4)
}
function goDetail() { router.push('/fund/' + props.fundCode) }
</script>

<template>
  <div class="fund-card" @click="goDetail">
    <div class="fund-info">
      <div class="fund-name">{{ fundName }}</div>
      <div class="fund-code">{{ fundCode }}<span v-if="fundType" class="fund-type">{{ fundType }}</span></div>
    </div>
    <div class="fund-nav">{{ fmtNav(unitNav) }}</div>
    <div class="fund-return" :class="returnClass">
      {{ fmtReturn(showEstimate ? estimateReturnPct : dailyReturnPct) }}
    </div>
  </div>
</template>

<style scoped>
.fund-card {
  display: flex; align-items: center; padding: 14px 0;
  border-bottom: 1px solid var(--color-divider); cursor: pointer;
  transition: background 0.15s; -webkit-tap-highlight-color: transparent;
}
.fund-card:last-child { border-bottom: none; }
.fund-card:hover { background: var(--color-bg-hover); }
.fund-info { flex: 1; min-width: 0; }
.fund-name {
  font-size: 14px; font-weight: 600; color: var(--color-text-primary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.fund-code {
  font-size: 12px; color: var(--color-text-tertiary); margin-top: 2px;
  display: flex; align-items: center; gap: 6px;
}
.fund-type {
  display: inline-block; padding: 0 5px; border: 1px solid var(--color-border);
  border-radius: 3px; font-size: 10px; color: var(--color-text-tertiary);
}
.fund-nav {
  font-size: 14px; font-weight: 600; color: var(--color-text-primary);
  text-align: right; min-width: 70px; margin-right: 16px;
  font-variant-numeric: tabular-nums;
}
.fund-return {
  min-width: 72px; text-align: right; font-size: 14px; font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.fund-return.up { color: var(--color-up); }
.fund-return.down { color: var(--color-down); }
.fund-return.flat { color: var(--color-text-secondary); }
</style>
