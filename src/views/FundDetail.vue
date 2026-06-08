<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFundDetail, getFundHistory, getFundBullets, sendFundBullet } from '@/api/fund'
import { listWatchlist, addWatchlist, deleteWatchlist, simulateHoldingTrade, getFundAlertSettings, saveFundAlertSettings } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import LineChart from '@/components/LineChart.vue'
import type { FundDetail as D, BulletComment, AlertRule } from '@/types'

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const toast = useToast()
const fundCode = computed(() => route.params.fundCode as string)
const detail = ref<D | null>(null)
const chartPoints = ref<{ date: string; value: number }[]>([])
const bullets = ref<BulletComment[]>([])
const currentRange = ref('1m'); const watchId = ref(0); const loading = ref(true)
const barrageInput = ref(''); const barrageColor = ref('#2563EB'); const barrageSending = ref(false)
const barrageColors = ['#2563EB','#E53E3E','#16A34A','#D97706','#7C3AED','#0F172A']
const showTrade = ref(false); const tradeType = ref<'BUY'|'SELL'>('BUY'); const tradeAmt = ref(''); const tradeShare = ref('')
const showAlert = ref(false); const alertRules = ref<AlertRule[]>([]); const alertSaving = ref(false)
const alertTypes = [{type:'ESTIMATE_RETURN',label:'估算涨跌'},{type:'OFFICIAL_RETURN',label:'官方涨跌'},{type:'NAV',label:'净值'},{type:'PROFIT_LOSS',label:'盈亏'}]
const ranges = [{key:'1w',label:'1周'},{key:'1m',label:'1月'},{key:'3m',label:'3月'},{key:'6m',label:'6月'},{key:'1y',label:'1年'},{key:'all',label:'全部'}]

async function load() {
  loading.value = true
  try {
    const [d, h]: any[] = await Promise.all([getFundDetail(fundCode.value), getFundHistory(fundCode.value, currentRange.value)])
    detail.value = d; const pts = Array.isArray(h) ? h : h?.points || []; chartPoints.value = pts.map((p:any) => ({date:p.navDate||p.date, value:p.unitNav||p.nav||0}))
  } catch {} finally { loading.value = false }
}
async function loadBullets() { try { bullets.value = (await getFundBullets(fundCode.value))?.items || [] } catch {} }
async function checkWatch() { if (!auth.isLoggedIn) return; try { watchId.value = ((await listWatchlist(auth.userId))||[]).find((w:any)=>w.fundCode===fundCode.value)?.id||0 } catch {} }
async function toggleWatch() {
  if (!auth.isLoggedIn) { router.push('/login'); return }
  if (watchId.value) { await deleteWatchlist(auth.userId, watchId.value); watchId.value = 0; toast.success('已移除') }
  else { await addWatchlist(auth.userId, {fundCode:fundCode.value}); await checkWatch(); toast.success('已添加') }
}
async function doBarrage() {
  const c = barrageInput.value.trim(); if (!c) return; if (c.length > 60) { toast.info('最多60字'); return }
  barrageSending.value = true
  try { barrageInput.value=''; bullets.value = (await sendFundBullet(fundCode.value,{content:c,color:barrageColor.value}))?.items||bullets.value; toast.success('已发送') } catch {} finally { barrageSending.value = false }
}
function openTrade(t:'BUY'|'SELL') { tradeType.value=t; tradeAmt.value=''; tradeShare.value=''; showTrade.value=true }
async function doTrade() {
  if (!auth.isLoggedIn) { router.push('/login'); return }
  const a=Number(tradeAmt.value), s=Number(tradeShare.value); if (!a && !s) { toast.info('请输入金额或份额'); return }
  try { await simulateHoldingTrade(auth.userId,{fundCode:fundCode.value,txnType:tradeType.value,amount:a||undefined,share:s||undefined}); showTrade.value=false; toast.success(tradeType.value==='BUY'?'买入成功':'卖出成功') } catch {}
}
async function openAlerts() { if (!auth.isLoggedIn){router.push('/login');return} try{alertRules.value=await getFundAlertSettings(auth.userId,fundCode.value)||[]}catch{alertRules.value=[]} showAlert.value=true }
function addRule() { alertRules.value.push({id:0,fundCode:fundCode.value,ruleType:'ESTIMATE_RETURN',compareOp:'GTE',thresholdValue:0,enabled:true,remindMode:'IMMEDIATE'}) }
function rmRule(i:number) { alertRules.value.splice(i,1) }
async function saveAlerts() { alertSaving.value=true; try{await saveFundAlertSettings(auth.userId,fundCode.value,alertRules.value);showAlert.value=false;toast.success('已保存')}catch{}finally{alertSaving.value=false} }
function changeRange(k:string) { currentRange.value=k; load() }
const retCls = computed(() => { const v=detail.value?.dailyReturnPct||0; return v>0?'up':v<0?'down':'flat' })
onMounted(() => { load(); loadBullets(); checkWatch() })
</script>

<template>
  <div class="page">
    <div v-if="loading" class="skel-full"></div>
    <template v-else-if="detail">
      <!-- Breadcrumb -->
      <div class="breadcrumb">
        <span class="bc-link" @click="router.push('/')">首页</span>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18l6-6-6-6"/></svg>
        <span>{{ detail.fundName }}</span>
        <button class="watch-btn" :class="{on:watchId}" @click="toggleWatch">
          <svg v-if="!watchId" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14m-7-7h14"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="2"><path d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"/></svg>
          {{ watchId ? '已自选' : '自选' }}
        </button>
      </div>

      <!-- Price header card -->
      <div class="price-card" :class="retCls">
        <div class="pc-left">
          <div class="pc-main">
            <span class="pc-val">{{ detail.unitNav?.toFixed(4) || '--' }}</span>
            <span class="pc-arrow" :class="retCls">{{ (detail.dailyReturnPct||0) > 0 ? '▲' : (detail.dailyReturnPct||0) < 0 ? '▼' : '●' }}</span>
          </div>
          <div class="pc-change">
            <span class="pc-pct" :class="retCls">{{ (detail.dailyReturnPct>0?'+':'')+(detail.dailyReturnPct?.toFixed(2)||'0.00') }}%</span>
            <span class="pc-date">{{ detail.navDate }}</span>
          </div>
        </div>
        <div class="pc-right" v-if="detail.estimateReturnPct!=null">
          <div class="pc-est-label">实时估算</div>
          <div class="pc-est-val" :class="(detail.estimateReturnPct||0)>0?'up':(detail.estimateReturnPct||0)<0?'down':'flat'">
            {{ detail.estimateNav?.toFixed(4) || '--' }}
          </div>
          <div class="pc-est-pct" :class="(detail.estimateReturnPct||0)>0?'up':(detail.estimateReturnPct||0)<0?'down':'flat'">
            {{ (detail.estimateReturnPct>0?'+':'')+detail.estimateReturnPct.toFixed(2) }}%
          </div>
        </div>
      </div>

      <!-- Two-column layout -->
      <div class="detail-grid">
        <!-- Left: chart + actions + barrage -->
        <div class="detail-left">
          <div class="panel">
            <div class="panel-head">
              <div class="range-bar">
                <button v-for="r in ranges" :key="r.key" class="range-btn" :class="{active:currentRange===r.key}" @click="changeRange(r.key)">{{ r.label }}</button>
              </div>
            </div>
            <div class="chart-area"><LineChart :points="chartPoints" :height="300" /></div>
          </div>
          <!-- Actions -->
          <div class="action-bar">
            <button class="act buy" @click="openTrade('BUY')">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 19V5m-7 7l7-7 7 7"/></svg>
              模拟买入
            </button>
            <button class="act sell" @click="openTrade('SELL')">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14m7-7l-7 7-7-7"/></svg>
              模拟卖出
            </button>
            <button class="act alert" @click="openAlerts">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 01-3.46 0"/></svg>
              提醒设置
            </button>
          </div>
          <!-- Bullets -->
          <div class="panel">
            <div class="panel-head"><span class="panel-title">弹幕</span><span class="panel-count">{{ bullets.length }}条</span></div>
            <div class="barrage-form">
              <input v-model="barrageInput" class="bi" placeholder="发一条弹幕..." maxlength="60" @keyup.enter="doBarrage" />
              <div class="bdots"><span v-for="c in barrageColors" :key="c" class="bdot" :class="{active:barrageColor===c}" :style="'background:'+c" @click="barrageColor=c"></span></div>
              <button class="bsend" :disabled="barrageSending" @click="doBarrage">{{ barrageSending?'...':'发送' }}</button>
            </div>
            <div class="bullet-list" v-if="bullets.length">
              <div v-for="b in bullets" :key="b.id" class="brow"><span class="bd" :style="'background:'+b.color"></span><span class="bt">{{ b.content }}</span></div>
            </div>
            <div v-else class="empty-hint">暂无弹幕，来发一条吧</div>
          </div>
        </div>

        <!-- Right: info -->
        <div class="detail-right">
          <div class="panel">
            <div class="panel-head"><span class="panel-title">基金信息</span></div>
            <div class="info-grid">
              <div class="ig"><span class="ik">基金代码</span><span class="iv">{{ fundCode }}</span></div>
              <div class="ig"><span class="ik">基金类型</span><span class="iv"><span class="type-badge">{{ detail.fundType || '--' }}</span></span></div>
              <div class="ig"><span class="ik">基金公司</span><span class="iv">{{ detail.companyName || '--' }}</span></div>
              <div class="ig"><span class="ik">基金经理</span><span class="iv">{{ detail.managerName || '--' }}</span></div>
              <div class="ig"><span class="ik">风险等级</span><span class="iv"><span class="risk-badge" :class="(detail.riskLevel||'').includes('高')?'high':(detail.riskLevel||'').includes('低')?'low':'mid'">{{ detail.riskLevel || '--' }}</span></span></div>
              <div class="ig"><span class="ik">成立日期</span><span class="iv">{{ detail.inceptionDate || '--' }}</span></div>
              <div class="ig"><span class="ik">单位净值</span><span class="iv highlight">{{ detail.unitNav?.toFixed(4) || '--' }}</span></div>
              <div class="ig"><span class="ik">累计净值</span><span class="iv">{{ detail.accumulatedNav?.toFixed(4) || '--' }}</span></div>
              <div class="ig"><span class="ik">业绩基准</span><span class="iv small">{{ detail.benchmark || '--' }}</span></div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Trade popup -->
    <Teleport to="body">
      <div v-if="showTrade" class="mask" @click.self="showTrade=false">
        <div class="popup"><div class="ph"><span class="pt">{{ tradeType==='BUY'?'模拟买入':'模拟卖出' }}</span><button class="px" @click="showTrade=false">&times;</button></div>
          <div class="pb"><label class="fl">金额（元）</label><input v-model="tradeAmt" type="number" class="fi" placeholder="例如 1000" /><label class="fl">份额</label><input v-model="tradeShare" type="number" class="fi" placeholder="例如 500" /></div>
          <button class="pc" @click="doTrade">确认{{ tradeType==='BUY'?'买入':'卖出' }}</button>
        </div>
      </div>
    </Teleport>
    <!-- Alert popup -->
    <Teleport to="body">
      <div v-if="showAlert" class="mask" @click.self="showAlert=false">
        <div class="popup"><div class="ph"><span class="pt">提醒设置</span><button class="px" @click="showAlert=false">&times;</button></div>
          <div class="pb">
            <div v-for="(r,i) in alertRules" :key="i" class="ar"><select v-model="r.ruleType" class="fs"><option v-for="t in alertTypes" :key="t.type" :value="t.type">{{ t.label }}</option></select><select v-model="r.compareOp" class="fs sm"><option value="GT">></option><option value="GTE">>=</option><option value="LT"><</option><option value="LTE"><=</option></select><input v-model.number="r.thresholdValue" type="number" class="fi sm" /><button class="db" @click="rmRule(i)">&times;</button></div>
            <button class="ab" @click="addRule">+ 添加规则</button>
          </div>
          <button class="pc" :disabled="alertSaving" @click="saveAlerts">{{ alertSaving?'...':'保存' }}</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-text-tertiary); margin-bottom: 14px; }
.bc-link { cursor: pointer; color: var(--color-primary); font-weight: 500; }
.watch-btn {
  margin-left: auto; display: flex; align-items: center; gap: 4px;
  border: none; border-radius: 6px; padding: 6px 14px; font-size: 12px; font-weight: 600;
  background: var(--color-primary); color: #fff; transition: all 0.15s;
}
.watch-btn:hover { opacity: 0.9; }
.watch-btn.on { background: var(--color-bg-secondary); color: var(--color-text-secondary); }

/* Price card */
.price-card {
  display: flex; justify-content: space-between; align-items: center;
  padding: 20px 24px; margin-bottom: 16px; border-radius: 12px;
  background: var(--color-bg-card); box-shadow: 0 2px 12px var(--color-shadow);
  border-left: 4px solid var(--color-border);
}
.price-card.up { border-left-color: var(--color-up); background: linear-gradient(135deg, var(--color-bg-card) 80%, var(--color-up-bg) 100%); }
.price-card.down { border-left-color: var(--color-down); background: linear-gradient(135deg, var(--color-bg-card) 80%, var(--color-down-bg) 100%); }

.pc-left { }
.pc-main { display: flex; align-items: baseline; gap: 8px; }
.pc-val { font-size: 32px; font-weight: 900; font-variant-numeric: tabular-nums; letter-spacing: -0.5px; color: var(--color-text-primary); }
.pc-arrow { font-size: 14px; }
.pc-arrow.up { color: var(--color-up); }
.pc-arrow.down { color: var(--color-down); }
.pc-arrow.flat { color: var(--color-text-tertiary); }
.pc-change { display: flex; align-items: center; gap: 12px; margin-top: 6px; }
.pc-pct { font-size: 18px; font-weight: 800; font-variant-numeric: tabular-nums; padding: 2px 8px; border-radius: 4px; }
.pc-pct.up { color: var(--color-up); background: var(--color-up-bg); }
.pc-pct.down { color: var(--color-down); background: var(--color-down-bg); }
.pc-pct.flat { color: var(--color-text-secondary); }
.pc-date { font-size: 12px; color: var(--color-text-tertiary); }

.pc-right { text-align: right; padding: 10px 16px; background: var(--color-bg-secondary); border-radius: 8px; }
.pc-est-label { font-size: 11px; color: var(--color-text-tertiary); margin-bottom: 4px; }
.pc-est-val { font-size: 18px; font-weight: 800; font-variant-numeric: tabular-nums; }
.pc-est-val.up { color: var(--color-up); }
.pc-est-val.down { color: var(--color-down); }
.pc-est-pct { font-size: 14px; font-weight: 700; margin-top: 2px; }
.pc-est-pct.up { color: var(--color-up); }
.pc-est-pct.down { color: var(--color-down); }

/* Layout */
.detail-grid { display: grid; grid-template-columns: 1fr 300px; gap: 16px; }
.detail-left, .detail-right { display: flex; flex-direction: column; gap: 12px; }

.panel { background: var(--color-bg-card); border-radius: 10px; box-shadow: 0 2px 8px var(--color-shadow); overflow: hidden; }
.panel-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--color-divider); }
.panel-title { font-size: 13px; font-weight: 700; color: var(--color-text-primary); }
.panel-count { font-size: 11px; color: var(--color-text-tertiary); }
.range-bar { display: inline-flex; gap: 1px; padding: 2px; background: var(--color-bg-secondary); border-radius: 6px; }
.range-btn { border: none; border-radius: 5px; padding: 5px 12px; background: transparent; color: var(--color-text-tertiary); font-size: 12px; font-weight: 500; transition: all 0.15s; }
.range-btn.active { background: var(--color-bg-card); color: var(--color-primary); font-weight: 700; box-shadow: 0 1px 3px var(--color-shadow); }
.chart-area { padding: 14px; }

/* Actions */
.action-bar { display: flex; gap: 8px; }
.act {
  flex: 1; display: flex; align-items: center; justify-content: center; gap: 6px;
  border: none; border-radius: 10px; padding: 12px; font-size: 13px; font-weight: 700;
  transition: all 0.15s;
}
.act:hover { transform: translateY(-1px); box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
.act.buy { background: var(--color-up-bg); color: var(--color-up); }
.act.sell { background: var(--color-down-bg); color: var(--color-down); }
.act.alert { background: var(--color-primary-light); color: var(--color-primary); }

/* Barrage */
.barrage-form { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-bottom: 1px solid var(--color-divider); }
.bi { flex: 1; height: 32px; border: 1px solid var(--color-border); border-radius: 6px; padding: 0 10px; font-size: 12px; color: var(--color-text-primary); background: var(--color-bg); outline: none; }
.bi:focus { border-color: var(--color-primary); }
.bdots { display: flex; gap: 4px; }
.bdot { width: 16px; height: 16px; border-radius: 8px; cursor: pointer; border: 2px solid transparent; transition: all 0.15s; }
.bdot:hover { transform: scale(1.2); }
.bdot.active { border-color: var(--color-text-primary); transform: scale(1.1); }
.bsend { border: none; border-radius: 6px; padding: 6px 14px; background: var(--color-primary); color: #fff; font-size: 12px; font-weight: 600; }
.bsend:disabled { opacity: 0.5; }
.bullet-list { max-height: 200px; overflow-y: auto; }
.brow { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-bottom: 1px solid var(--color-divider); font-size: 12px; }
.brow:last-child { border-bottom: none; }
.bd { width: 6px; height: 6px; border-radius: 3px; flex-shrink: 0; }
.bt { color: var(--color-text-primary); }
.empty-hint { padding: 20px; text-align: center; font-size: 12px; color: var(--color-text-tertiary); }

/* Info grid */
.info-grid { padding: 4px 0; }
.ig { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--color-divider); font-size: 12px; }
.ig:last-child { border-bottom: none; }
.ik { color: var(--color-text-tertiary); }
.iv { color: var(--color-text-primary); font-weight: 500; }
.iv.highlight { font-weight: 700; color: var(--color-primary); }
.iv.small { font-size: 11px; max-width: 160px; text-align: right; }
.type-badge { display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; background: var(--color-primary-light); color: var(--color-primary); font-weight: 600; }
.risk-badge { display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; font-weight: 600; }
.risk-badge.high { background: var(--color-up-bg); color: var(--color-up); }
.risk-badge.mid { background: #FEF3C7; color: #92400E; }
.risk-badge.low { background: var(--color-down-bg); color: var(--color-down); }

/* Popup */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 200; }
.popup { width: 420px; background: var(--color-bg-card); border-radius: 12px; padding: 24px; box-shadow: 0 8px 32px rgba(0,0,0,0.15); }
.ph { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.pt { font-size: 16px; font-weight: 700; color: var(--color-text-primary); }
.px { width: 30px; height: 30px; border: none; border-radius: 15px; background: var(--color-bg-secondary); color: var(--color-text-tertiary); font-size: 18px; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.pb { margin-bottom: 16px; }
.fl { display: block; font-size: 12px; color: var(--color-text-tertiary); margin: 10px 0 4px; font-weight: 500; }
.fi { width: 100%; height: 38px; border: 1px solid var(--color-border); border-radius: 8px; padding: 0 12px; font-size: 13px; color: var(--color-text-primary); background: var(--color-bg); outline: none; box-sizing: border-box; }
.fi:focus { border-color: var(--color-primary); box-shadow: 0 0 0 2px rgba(37,99,235,0.1); }
.fi.sm { width: 80px; }
.fs { height: 34px; border: 1px solid var(--color-border); border-radius: 6px; padding: 0 8px; font-size: 12px; color: var(--color-text-primary); background: var(--color-bg); outline: none; }
.fs.sm { width: 65px; }
.pc { width: 100%; height: 42px; border: none; border-radius: 10px; background: var(--color-primary); color: #fff; font-size: 14px; font-weight: 700; cursor: pointer; transition: opacity 0.15s; }
.pc:hover { opacity: 0.9; }
.pc:disabled { opacity: 0.5; }
.ar { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; }
.db { width: 30px; height: 30px; border: none; border-radius: 6px; background: var(--color-up-bg); color: var(--color-up); font-size: 16px; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.ab { border: 1px dashed var(--color-border); border-radius: 8px; padding: 8px; width: 100%; background: transparent; color: var(--color-primary); font-size: 12px; font-weight: 500; cursor: pointer; }
.ab:hover { background: var(--color-primary-light); }

.skel-full { height: 500px; border-radius: 12px; background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>