<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getHoldingDashboard, simulateHoldingTrade } from '@/api/user'
import { useToast } from '@/composables/useToast'
import { useRouter } from 'vue-router'
import type { HoldingDashboard as DashboardType } from '@/types'

const auth = useAuthStore()
const router = useRouter()
const toast = useToast()
const dashboard = ref<DashboardType | null>(null)
const loading = ref(true)

// Trade popup
const showTradePopup = ref(false)
const tradeFundCode = ref('')
const tradeFundName = ref('')
const tradeType = ref<'BUY' | 'SELL'>('BUY')
const tradeAmount = ref('')
const tradeShare = ref('')

const summary = computed(() => dashboard.value?.summary)
const holdings = computed(() => dashboard.value?.holdings || [])

onMounted(async () => {
  if (!auth.isLoggedIn) return
  try {
    dashboard.value = await getHoldingDashboard(auth.userId)
  } catch { /* silent */ } finally {
    loading.value = false
  }
})

async function reload() {
  loading.value = true
  try {
    dashboard.value = await getHoldingDashboard(auth.userId)
  } catch { /* silent */ } finally {
    loading.value = false
  }
}

function openTrade(fundCode: string, fundName: string, type: 'BUY' | 'SELL') {
  tradeFundCode.value = fundCode
  tradeFundName.value = fundName
  tradeType.value = type
  tradeAmount.value = ''
  tradeShare.value = ''
  showTradePopup.value = true
}

async function doTrade() {
  const amount = Number(tradeAmount.value)
  const share = Number(tradeShare.value)
  if (!amount && !share) { toast.info('请输入金额或份额'); return }
  try {
    await simulateHoldingTrade(auth.userId, {
      fundCode: tradeFundCode.value,
      txnType: tradeType.value,
      amount: amount || undefined,
      share: share || undefined
    })
    showTradePopup.value = false
    toast.success(tradeType.value === 'BUY' ? '买入成功' : '卖出成功')
    reload()
  } catch { /* silent */ }
}

function fmt(val?: number) {
  if (val === null || val === undefined) return '--'
  return val.toFixed(2)
}

function fmtPct(val?: number) {
  if (val === null || val === undefined) return '--'
  return (val > 0 ? '+' : '') + val.toFixed(2) + '%'
}

function valClass(val?: number) {
  if (!val) return 'flat'
  return val > 0 ? 'up' : 'down'
}

function goFund(code: string) { router.push('/fund/' + code) }
</script>

<template>
  <div class="page holdings-page">
    <div class="page-header">
      <h1 class="page-title">我的持仓</h1>
      <button class="refresh-btn" @click="reload" :disabled="loading">
        <svg :class="{ spinning: loading }" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 4v6h6"/><path d="M3.51 15a9 9 0 102.13-9.36L1 10"/></svg>
      </button>
    </div>

    <!-- Skeleton -->
    <template v-if="loading">
      <div class="skeleton-card"></div>
      <div class="skeleton-list">
        <div class="skeleton-item" v-for="i in 3" :key="i"></div>
      </div>
    </template>

    <template v-else>
      <!-- Summary -->
      <div class="summary-card" v-if="summary">
        <div class="summary-row">
          <div class="summary-item">
            <div class="summary-label">总市值</div>
            <div class="summary-value">{{ fmt(summary.totalMarketValue) }}</div>
          </div>
          <div class="summary-item">
            <div class="summary-label">总成本</div>
            <div class="summary-value">{{ fmt(summary.totalCost) }}</div>
          </div>
        </div>
        <div class="summary-row">
          <div class="summary-item">
            <div class="summary-label">总盈亏</div>
            <div class="summary-value" :class="valClass(summary.totalProfitLoss)">{{ fmt(summary.totalProfitLoss) }}</div>
          </div>
          <div class="summary-item">
            <div class="summary-label">收益率</div>
            <div class="summary-value" :class="valClass(summary.totalProfitLossPct)">{{ fmtPct(summary.totalProfitLossPct) }}</div>
          </div>
        </div>
      </div>

      <!-- Holdings list -->
      <div class="section" v-if="holdings.length">
        <div class="section-title">持仓明细</div>
        <div class="holding-list">
          <div v-for="h in holdings" :key="h.id" class="holding-item">
            <div class="holding-main" @click="goFund(h.fundCode)">
              <div class="holding-top">
                <span class="holding-name">{{ h.fundName }}</span>
                <span class="holding-pnl" :class="valClass(h.profitLossPct)">{{ fmtPct(h.profitLossPct) }}</span>
              </div>
              <div class="holding-bottom">
                <span class="holding-code">{{ h.fundCode }}</span>
                <span class="holding-amount">盈亏 {{ fmt(h.profitLossAmount) }}</span>
              </div>
            </div>
            <div class="holding-actions">
              <button class="trade-btn buy" @click.stop="openTrade(h.fundCode, h.fundName, 'BUY')">买</button>
              <button class="trade-btn sell" @click.stop="openTrade(h.fundCode, h.fundName, 'SELL')">卖</button>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="empty-state">
        <div class="empty-icon">&#128202;</div>
        <div>暂无持仓记录</div>
        <router-link to="/search" class="empty-link">去添加基金</router-link>
      </div>
    </template>

    <!-- Trade popup -->
    <Teleport to="body">
      <div v-if="showTradePopup" class="popup-mask" @click.self="showTradePopup = false">
        <div class="popup-panel">
          <div class="popup-head">
            <span class="popup-title">{{ tradeType === 'BUY' ? '模拟买入' : '模拟卖出' }} · {{ tradeFundName }}</span>
            <button class="popup-close" @click="showTradePopup = false">&times;</button>
          </div>
          <div class="popup-body">
            <div class="form-row">
              <label>金额（元）</label>
              <input v-model="tradeAmount" type="number" class="form-input" placeholder="例如 1000" />
            </div>
            <div class="form-row">
              <label>份额</label>
              <input v-model="tradeShare" type="number" class="form-input" placeholder="例如 500" />
            </div>
          </div>
          <button class="popup-confirm" @click="doTrade">确认{{ tradeType === 'BUY' ? '买入' : '卖出' }}</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>

.page-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16px;
}
.page-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); margin: 0; }
.refresh-btn {
  width: 36px; height: 36px; border: none; border-radius: 8px;
  background: var(--color-bg-card); color: var(--color-text-secondary);
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.refresh-btn:disabled { opacity: 0.5; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.spinning { animation: spin 0.8s linear infinite; }

.skeleton-card {
  margin-bottom: 12px; height: 120px; border-radius: 12px;
  background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%);
  background-size: 200% 100%; animation: shimmer 1.5s infinite;
}
.skeleton-list { }
.skeleton-item {
  height: 64px; border-radius: 10px; margin-bottom: 8px;
  background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%);
  background-size: 200% 100%; animation: shimmer 1.5s infinite;
}
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }

.empty-state { text-align: center; padding: 60px 20px; color: var(--color-text-secondary); }
.empty-icon { font-size: 36px; margin-bottom: 8px; }
.empty-link { color: var(--color-primary); text-decoration: none; font-size: 14px; margin-top: 8px; display: inline-block; }

.summary-card {
  margin-bottom: 16px; padding: 16px; border-radius: 12px; background: var(--color-bg-card);
}
.summary-row { display: flex; gap: 12px; }
.summary-row + .summary-row { margin-top: 12px; }
.summary-item { flex: 1; }
.summary-label { font-size: 12px; color: var(--color-text-secondary); }
.summary-value { font-size: 18px; font-weight: 800; color: var(--color-text-primary); margin-top: 2px; }
.summary-value.up { color: var(--color-up); }
.summary-value.down { color: var(--color-down); }

.section { margin-bottom: 16px; }
.section-title { font-size: 15px; font-weight: 700; color: var(--color-text-primary); margin-bottom: 8px; }
.holding-list { background: var(--color-bg-card); border-radius: 10px; overflow: hidden; }
.holding-item {
  display: flex; align-items: center;
  padding: 12px 14px; border-bottom: 1px solid var(--color-border);
}
.holding-item:last-child { border-bottom: none; }
.holding-main { flex: 1; cursor: pointer; min-width: 0; }
.holding-main:hover { opacity: 0.8; }
.holding-top { display: flex; justify-content: space-between; align-items: center; }
.holding-name { font-size: 14px; font-weight: 600; color: var(--color-text-primary); }
.holding-pnl { font-size: 14px; font-weight: 700; }
.holding-pnl.up { color: var(--color-up); }
.holding-pnl.down { color: var(--color-down); }
.holding-bottom { display: flex; justify-content: space-between; margin-top: 4px; }
.holding-code { font-size: 12px; color: var(--color-text-secondary); }
.holding-amount { font-size: 12px; color: var(--color-text-secondary); }
.holding-actions { display: flex; gap: 4px; margin-left: 10px; }
.trade-btn {
  width: 32px; height: 32px; border: none; border-radius: 6px;
  font-size: 12px; font-weight: 700; cursor: pointer;
}
.trade-btn.buy { background: #FEE2E2; color: #DC2626; }
.trade-btn.sell { background: #DCFCE7; color: #16A34A; }

.popup-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center; z-index: 200;
}
.popup-panel {
  width: 100%; max-width: 480px; background: var(--color-bg-card);
  border-radius: var(--radius-lg); padding: 20px;
}
.popup-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.popup-title { font-size: 17px; font-weight: 700; color: var(--color-text-primary); }
.popup-close {
  width: 32px; height: 32px; border: none; border-radius: 16px;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
  font-size: 18px; cursor: pointer; display: flex; align-items: center; justify-content: center;
}
.popup-body { margin-bottom: 16px; }
.form-row { margin-bottom: 12px; }
.form-row label { display: block; font-size: 13px; color: var(--color-text-secondary); margin-bottom: 4px; }
.form-input {
  width: 100%; height: 40px; border: 1px solid var(--color-border); border-radius: 8px;
  padding: 0 12px; font-size: 14px; color: var(--color-text-primary);
  background: var(--color-bg); outline: none; box-sizing: border-box;
}
.form-input:focus { border-color: var(--color-primary); }
.popup-confirm {
  width: 100%; height: 44px; border: none; border-radius: 10px;
  background: var(--color-primary); color: #fff;
  font-size: 15px; font-weight: 700; cursor: pointer;
}
</style>
