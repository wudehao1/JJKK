<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFundDetail, getFundHistory, getFundBullets, sendFundBullet } from '@/api/fund'
import { listWatchlist, addWatchlist, deleteWatchlist, simulateHoldingTrade, getFundAlertSettings, saveFundAlertSettings } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import LineChart from '@/components/LineChart.vue'
import type { FundDetail as DetailType, BulletComment, AlertRule } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const toast = useToast()

const fundCode = computed(() => route.params.fundCode as string)
const detail = ref<DetailType | null>(null)
const chartPoints = ref<{ date: string; value: number }[]>([])
const bullets = ref<BulletComment[]>([])
const currentRange = ref('1m')
const watchId = ref(0)
const loading = ref(true)

// Barrage
const barrageInput = ref('')
const barrageColor = ref('#1677F2')
const barrageSending = ref(false)
const barrageColors = ['#1677F2', '#D94252', '#18A875', '#F59E0B', '#8B5CF6', '#111827']

// Trade popup
const showTradePopup = ref(false)
const tradeType = ref<'BUY' | 'SELL'>('BUY')
const tradeAmount = ref('')
const tradeShare = ref('')

// Alert popup
const showAlertPopup = ref(false)
const alertRules = ref<AlertRule[]>([])
const alertTypes = [
  { type: 'ESTIMATE_RETURN', label: '估算涨跌幅', unit: '%' },
  { type: 'OFFICIAL_RETURN', label: '官方涨跌幅', unit: '%' },
  { type: 'NAV', label: '净值', unit: '' },
  { type: 'PROFIT_LOSS', label: '盈亏金额', unit: '元' }
]
const alertSaving = ref(false)

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
    toast.success('已移除自选')
  } else {
    await addWatchlist(auth.userId, { fundCode: fundCode.value })
    await checkWatch()
    toast.success('已添加自选')
  }
}

async function doSendBarrage() {
  const content = barrageInput.value.trim()
  if (!content) { toast.info('先写点弹幕内容'); return }
  if (content.length > 60) { toast.info('弹幕最多60个字'); return }
  barrageSending.value = true
  try {
    const data = await sendFundBullet(fundCode.value, { content, color: barrageColor.value })
    barrageInput.value = ''
    bullets.value = data?.items || bullets.value
    toast.success('弹幕发送成功')
  } catch { /* silent */ } finally {
    barrageSending.value = false
  }
}

// Trade
function openTrade(type: 'BUY' | 'SELL') {
  tradeType.value = type
  tradeAmount.value = ''
  tradeShare.value = ''
  showTradePopup.value = true
}

async function doTrade() {
  if (!auth.isLoggedIn) { router.push('/login'); return }
  const amount = Number(tradeAmount.value)
  const share = Number(tradeShare.value)
  if (!amount && !share) { toast.info('请输入金额或份额'); return }
  try {
    await simulateHoldingTrade(auth.userId, {
      fundCode: fundCode.value,
      txnType: tradeType.value,
      amount: amount || undefined,
      share: share || undefined
    })
    showTradePopup.value = false
    toast.success(tradeType.value === 'BUY' ? '买入模拟成功' : '卖出模拟成功')
  } catch { /* silent */ }
}

// Alerts
async function openAlerts() {
  if (!auth.isLoggedIn) { router.push('/login'); return }
  try {
    alertRules.value = await getFundAlertSettings(auth.userId, fundCode.value) || []
  } catch {
    alertRules.value = []
  }
  showAlertPopup.value = true
}

function addAlertRule() {
  alertRules.value.push({
    id: 0, fundCode: fundCode.value, ruleType: 'ESTIMATE_RETURN',
    compareOp: 'GTE', thresholdValue: 0, enabled: true, remindMode: 'IMMEDIATE'
  })
}

function removeAlertRule(index: number) {
  alertRules.value.splice(index, 1)
}

async function saveAlerts() {
  alertSaving.value = true
  try {
    await saveFundAlertSettings(auth.userId, fundCode.value, alertRules.value)
    showAlertPopup.value = false
    toast.success('提醒设置已保存')
  } catch { /* silent */ } finally {
    alertSaving.value = false
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
      <!-- Price -->
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
          <button v-for="r in ranges" :key="r.key" class="range-tab" :class="{ active: currentRange === r.key }" @click="changeRange(r.key)">{{ r.label }}</button>
        </div>
        <LineChart :points="chartPoints" :height="200" />
      </div>

      <!-- Actions -->
      <div class="action-bar">
        <button class="action-btn buy" @click="openTrade('BUY')">模拟买入</button>
        <button class="action-btn sell" @click="openTrade('SELL')">模拟卖出</button>
        <button class="action-btn alert" @click="openAlerts">提醒设置</button>
      </div>

      <!-- Info -->
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
      <div class="bullet-section">
        <div class="section-title">弹幕 ({{ bullets.length }})</div>
        <!-- Send -->
        <div class="barrage-send-row">
          <input v-model="barrageInput" class="barrage-input" placeholder="发一条弹幕..." maxlength="60" @keyup.enter="doSendBarrage" />
          <div class="barrage-colors">
            <span v-for="c in barrageColors" :key="c" class="color-dot" :class="{ active: barrageColor === c }" :style="'background:' + c" @click="barrageColor = c"></span>
          </div>
          <button class="send-btn" :class="{ disabled: barrageSending }" :disabled="barrageSending" @click="doSendBarrage">
            {{ barrageSending ? '...' : '发送' }}
          </button>
        </div>
        <!-- List -->
        <div class="bullet-list" v-if="bullets.length">
          <div v-for="b in bullets" :key="b.id" class="bullet-item">
            <span class="bullet-dot" :style="'background:' + b.color"></span>
            <span class="bullet-text">{{ b.content }}</span>
          </div>
        </div>
        <div v-else class="empty-hint">暂无弹幕</div>
      </div>
    </template>

    <!-- Trade popup -->
    <Teleport to="body">
      <div v-if="showTradePopup" class="popup-mask" @click.self="showTradePopup = false">
        <div class="popup-panel">
          <div class="popup-head">
            <span class="popup-title">{{ tradeType === 'BUY' ? '模拟买入' : '模拟卖出' }}</span>
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

    <!-- Alert popup -->
    <Teleport to="body">
      <div v-if="showAlertPopup" class="popup-mask" @click.self="showAlertPopup = false">
        <div class="popup-panel alert-panel">
          <div class="popup-head">
            <span class="popup-title">提醒设置</span>
            <button class="popup-close" @click="showAlertPopup = false">&times;</button>
          </div>
          <div class="popup-body">
            <div v-for="(rule, i) in alertRules" :key="i" class="alert-rule">
              <select v-model="rule.ruleType" class="form-select">
                <option v-for="t in alertTypes" :key="t.type" :value="t.type">{{ t.label }}</option>
              </select>
              <select v-model="rule.compareOp" class="form-select small">
                <option value="GT">大于</option>
                <option value="GTE">大于等于</option>
                <option value="LT">小于</option>
                <option value="LTE">小于等于</option>
              </select>
              <input v-model.number="rule.thresholdValue" type="number" class="form-input small" />
              <button class="remove-btn" @click="removeAlertRule(i)">&times;</button>
            </div>
            <button class="add-rule-btn" @click="addAlertRule">+ 添加规则</button>
          </div>
          <button class="popup-confirm" :disabled="alertSaving" @click="saveAlerts">
            {{ alertSaving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </Teleport>
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

.action-bar {
  display: flex; gap: 8px; padding: 12px 16px;
}
.action-btn {
  flex: 1; border: none; border-radius: 8px; padding: 10px 0;
  font-size: 13px; font-weight: 700; cursor: pointer;
}
.action-btn.buy { background: #FEE2E2; color: #DC2626; }
.action-btn.sell { background: #DCFCE7; color: #16A34A; }
.action-btn.alert { background: var(--color-bg-secondary); color: var(--color-primary); }

.section-title { font-size: 15px; font-weight: 700; color: var(--color-text-primary); margin-bottom: 8px; }
.info-section { padding: 0 16px 16px; }
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
.barrage-send-row {
  display: flex; align-items: center; gap: 8px; margin-bottom: 10px;
}
.barrage-input {
  flex: 1; height: 36px; border: 1px solid var(--color-border); border-radius: 18px;
  padding: 0 14px; font-size: 13px; color: var(--color-text-primary);
  background: var(--color-bg-card); outline: none;
}
.barrage-input:focus { border-color: var(--color-primary); }
.barrage-colors { display: flex; gap: 4px; }
.color-dot {
  width: 18px; height: 18px; border-radius: 9px; cursor: pointer;
  border: 2px solid transparent; transition: border-color 0.15s;
}
.color-dot.active { border-color: var(--color-text-primary); }
.send-btn {
  border: none; border-radius: 18px; padding: 6px 16px;
  background: var(--color-primary); color: #fff;
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.send-btn.disabled { opacity: 0.5; }
.bullet-list { background: var(--color-bg-card); border-radius: 10px; padding: 8px; }
.bullet-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; }
.bullet-dot { width: 8px; height: 8px; border-radius: 4px; flex-shrink: 0; }
.bullet-text { font-size: 13px; color: var(--color-text-primary); }
.empty-hint { text-align: center; padding: 16px; color: var(--color-text-tertiary); font-size: 13px; }

/* Popups */
.popup-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: flex-end; justify-content: center; z-index: 200;
}
.popup-panel {
  width: 100%; max-width: 480px; background: var(--color-bg-card);
  border-radius: 16px 16px 0 0; padding: 20px;
}
.alert-panel { max-height: 70vh; overflow-y: auto; }
.popup-head {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
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
.form-input.small { width: 80px; }
.form-select {
  height: 36px; border: 1px solid var(--color-border); border-radius: 6px;
  padding: 0 8px; font-size: 13px; color: var(--color-text-primary);
  background: var(--color-bg); outline: none;
}
.form-select.small { width: 90px; }
.popup-confirm {
  width: 100%; height: 44px; border: none; border-radius: 10px;
  background: var(--color-primary); color: #fff;
  font-size: 15px; font-weight: 700; cursor: pointer;
}
.popup-confirm:disabled { opacity: 0.6; }
.alert-rule { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; }
.remove-btn {
  width: 32px; height: 32px; border: none; border-radius: 6px;
  background: #FEE2E2; color: #DC2626; font-size: 16px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
}
.add-rule-btn {
  border: 1px dashed var(--color-border); border-radius: 8px;
  padding: 8px; width: 100%; background: transparent;
  color: var(--color-primary); font-size: 13px; cursor: pointer;
}
</style>
