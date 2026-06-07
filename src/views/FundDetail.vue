<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFundDetail, getFundHistory, getFundBullets } from '@/api/fund'
import { listWatchlist, addWatchlist, deleteWatchlist } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import LineChart from '@/components/LineChart.vue'
import type { FundDetail as DetailType, BulletComment } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const fundCode = computed(() => route.params.fundCode as string)
const detail = ref<DetailType | null>(null)
const chartPoints = ref<{ date: string; value: number }[]>([])
const bullets = ref<BulletComment[]>([])
const currentRange = ref('1m')
const watchId = ref(0)
const loading = ref(true)

const ranges = [
  { key: '1w', label: '近1周' },
  { key: '1m', label: '近1月' },
  { key: '3m', label: '近3月' },
  { key: '6m', label: '近6月' },
  { key: '1y', label: '近1年' },
  { key: 'all', label: '全部' }
]

async function load() {
  loading.value = true
  try {
    const [d, hist] = await Promise.all([
      getFundDetail(fundCode.value),
      getFundHistory(fundCode.value, currentRange.value)
    ])
    detail.value = d
    if (Array.isArray(hist)) {
      chartPoints.value = hist.map((p: any) => ({ date: p.date, value: p.nav || p.unitNav || 0 }))
    }
  } catch { /* silent */ } finally {
    loading.value = false
  }
}

async function loadBullets() {
  try {
    const data = await getFundBullets(fundCode.value)
    bullets.value = data?.items || []
  } catch { /* silent */ }
}

async function checkWatch() {
  if (!auth.isLoggedIn) return
  try {
    const list = await listWatchlist(auth.userId)
    const found = (list || []).find((w: any) => w.fundCode === fundCode.value)
    watchId.value = found?.id || 0
  } catch { /* silent */ }
}

async function toggleWatch() {
  if (!auth.isLoggedIn) { router.push('/login'); return }
  if (watchId.value) {
    await deleteWatchlist(auth.userId, watchId.value)
    watchId.value = 0
  } else {
    await addWatchlist(auth.userId, { fundCode: fundCode.value })
    await checkWatch()
  }
}

function changeRange(key: string) {
  currentRange.value = key
  load()
}

function goBack() { router.back() }

const returnClass = computed(() => {
  const val = detail.value?.dailyReturnPct || 0
  if (val > 0) return 'up'
  if (val < 0) return 'down'
  return 'flat'
})

onMounted(() => { load(); loadBullets(); checkWatch() })
</script>

<template>
  <div class="page detail-page">
    <!-- Header -->
    <div class="detail-header">
      <button class="back-btn" @click="goBack">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5m7-7l-7 7 7 7"/></svg>
      </button>
      <div class="header-info">
        <div class="header-name">{{ detail?.fundName || fundCode }}</div>
        <div class="header-code">{{ fundCode }}</div>
      </div>
      <button class="watch-btn" :class="{ watched: watchId }" @click="toggleWatch">
        {{ watchId ? '已自选' : '+ 自选' }}
      </button>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <template v-else-if="detail">
      <!-- Price section -->
      <div class="price-section">
        <div class="price-nav" :class="returnClass">{{ detail.unitNav?.toFixed(4) || '--' }}</div>
        <div class="price-meta">
          <span :class="returnClass">
            {{ (detail.dailyReturnPct > 0 ? '+' : '') + (detail.dailyReturnPct?.toFixed(2) || '0.00') }}%
          </span>
          <span class="price-date">{{ detail.navDate }}</span>
        </div>
        <div class="estimate-row" v-if="detail.estimateReturnPct != null">
          <span class="estimate-label">实时估算</span>
          <span class="estimate-val" :class="(detail.estimateReturnPct || 0) > 0 ? 'up' : (detail.estimateReturnPct || 0) < 0 ? 'down' : 'flat'">
            {{ detail.estimateNav?.toFixed(4) || '--' }}
            ({{ (detail.estimateReturnPct > 0 ? '+' : '') + detail.estimateReturnPct.toFixed(2) }}%)
          </span>
        </div>
      </div>

      <!-- Chart -->
      <div class="chart-section">
        <div class="range-tabs">
          <button
            v-for="r in ranges"
            :key="r.key"
            class="range-tab"
            :class="{ active: currentRange === r.key }"
            @click="changeRange(r.key)"
          >{{ r.label }}</button>
        </div>
        <LineChart :points="chartPoints" :height="200" />
      </div>

      <!-- Info grid -->
      <div class="info-section">
        <div class="section-title">基金信息</div>
        <div class="info-grid">
          <div class="info-item"><span class="info-label">基金类型</span><span>{{ detail.fundType }}</span></div>
          <div class="info-item"><span class="info-label">基金公司</span><span>{{ detail.companyName }}</span></div>
          <div class="info-item"><span class="info-label">基金经理</span><span>{{ detail.managerName }}</span></div>
          <div class="info-item"><span class="info-label">风险等级</span><span>{{ detail.riskLevel }}</span></div>
          <div class="info-item"><span class="info-label">成立日期</span><span>{{ detail.inceptionDate }}</span></div>
          <div class="info-item"><span class="info-label">累计净值</span><span>{{ detail.accumulatedNav?.toFixed(4) || '--' }}</span></div>
        </div>
      </div>

      <!-- Bullets -->
      <div class="bullet-section" v-if="bullets.length">
        <div class="section-title">弹幕</div>
        <div class="bullet-list">
          <div v-for="b in bullets" :key="b.id" class="bullet-item">
            <span class="bullet-dot" :style="'background:' + b.color"></span>
            <span class="bullet-text">{{ b.content }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.detail-page { padding-bottom: 20px; }
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
.header-info { flex: 1; }
.header-name { font-size: 16px; font-weight: 700; color: var(--color-text-primary); }
.header-code { font-size: 12px; color: var(--color-text-secondary); }
.watch-btn {
  border: none; border-radius: 16px; padding: 6px 16px;
  background: var(--color-primary); color: #fff; font-size: 13px; font-weight: 600; cursor: pointer;
}
.watch-btn.watched { background: var(--color-bg-secondary); color: var(--color-text-secondary); }
.loading-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }

.price-section { padding: 16px; }
.price-nav { font-size: 32px; font-weight: 800; }
.price-nav.up { color: var(--color-up); }
.price-nav.down { color: var(--color-down); }
.price-nav.flat { color: var(--color-text-primary); }
.price-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 14px; font-weight: 600; }
.price-meta .up { color: var(--color-up); }
.price-meta .down { color: var(--color-down); }
.price-date { color: var(--color-text-secondary); font-weight: 400; font-size: 12px; }
.estimate-row { margin-top: 6px; font-size: 13px; }
.estimate-label { color: var(--color-text-secondary); margin-right: 6px; }
.estimate-val.up { color: var(--color-up); }
.estimate-val.down { color: var(--color-down); }

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

.section-title { font-size: 15px; font-weight: 700; color: var(--color-text-primary); margin-bottom: 8px; }
.info-section { padding: 16px; }
.info-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  background: var(--color-bg-card); border-radius: 10px; overflow: hidden;
}
.info-item {
  display: flex; justify-content: space-between; padding: 10px 14px;
  border-bottom: 1px solid var(--color-border); font-size: 13px; color: var(--color-text-primary);
}
.info-label { color: var(--color-text-secondary); }

.bullet-section { padding: 0 16px 16px; }
.bullet-list { background: var(--color-bg-card); border-radius: 10px; padding: 8px; }
.bullet-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; }
.bullet-dot { width: 8px; height: 8px; border-radius: 4px; flex-shrink: 0; }
.bullet-text { font-size: 13px; color: var(--color-text-primary); }
</style>
