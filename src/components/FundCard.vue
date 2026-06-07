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

const returnClass = computed(() => {
  const val = props.estimateReturnPct ?? props.dailyReturnPct ?? 0
  if (val > 0) return 'up'
  if (val < 0) return 'down'
  return 'flat'
})

function formatReturn(val?: number) {
  if (val === null || val === undefined) return '--'
  const sign = val > 0 ? '+' : ''
  return sign + val.toFixed(2) + '%'
}

function formatNav(val?: number) {
  if (val === null || val === undefined) return '--'
  return val.toFixed(4)
}

function goDetail() {
  router.push('/fund/' + props.fundCode)
}
</script>

<template>
  <div class="fund-card" @click="goDetail">
    <div class="fund-left">
      <div class="fund-name">{{ fundName }}</div>
      <div class="fund-code">{{ fundCode }}</div>
    </div>
    <div class="fund-center">
      <div class="fund-nav">{{ formatNav(unitNav) }}</div>
      <div class="fund-label">净值</div>
    </div>
    <div class="fund-right">
      <div class="fund-return" :class="returnClass">
        {{ formatReturn(showEstimate ? estimateReturnPct : dailyReturnPct) }}
      </div>
      <div class="fund-label">{{ showEstimate ? '估算' : '日涨跌' }}</div>
    </div>
  </div>
</template>

<style scoped>
.fund-card {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: var(--color-bg-card);
  border-bottom: 1px solid var(--color-border);
  cursor: pointer;
  transition: background 0.15s;
}
.fund-card:hover { background: var(--color-bg-hover); }
.fund-left { flex: 1; min-width: 0; }
.fund-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fund-code { font-size: 12px; color: var(--color-text-secondary); margin-top: 2px; }
.fund-center { text-align: right; margin-right: 20px; }
.fund-nav { font-size: 14px; font-weight: 600; color: var(--color-text-primary); }
.fund-right { text-align: right; min-width: 70px; }
.fund-return { font-size: 15px; font-weight: 700; }
.fund-return.up { color: var(--color-up); }
.fund-return.down { color: var(--color-down); }
.fund-return.flat { color: var(--color-text-secondary); }
.fund-label { font-size: 11px; color: var(--color-text-tertiary); margin-top: 2px; }
</style>
