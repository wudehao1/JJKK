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
        <span class="bc-sep">/</span>
        <span>{{ detail.fundName }}</span>
        <button class="watch-btn" :class="{on:watchId}" @click="toggleWatch">{{ watchId ? '已自选' : '+ 自选' }}</button>
      </div>

      <!-- Price header -->
      <div class="price-header">
        <div class="ph-main">
          <span class="ph-val" :class="retCls">{{ detail.unitNav?.toFixed(4) || '--' }}</span>
          <span class="ph-pct" :class="retCls">{{ (detail.dailyReturnPct>0?'+':'')+(detail.dailyReturnPct?.toFixed(2)||'0.00') }}%</span>
        </div>
        <div class="ph-sub">
          {{ detail.navDate }}
          <span v-if="detail.estimateReturnPct!=null" class="ph-est" :class="(detail.estimateReturnPct||0)>0?'up':(detail.estimateReturnPct||0)<0?'down':'flat'">
            估算 {{ detail.estimateNav?.toFixed(4) }} ({{ (detail.estimateReturnPct>0?'+':'')+detail.estimateReturnPct.toFixed(2) }}%)
          </span>
        </div>
      </div>

      <!-- Two-column layout -->
      <div class="detail-grid">
        <!-- Left: chart + barrage -->
        <div class="detail-left">
          <div class="panel">
            <div class="panel-head">
              <div class="range-bar">
                <button v-for="r in ranges" :key="r.key" class="range-btn" :class="{active:currentRange===r.key}" @click="changeRange(r.key)">{{ r.label }}</button>
              </div>
            </div>
            <div class="chart-area"><LineChart :points="chartPoints" :height="280" /></div>
          </div>
          <!-- Actions -->
          <div class="action-bar">
            <button class="act buy" @click="openTrade('BUY')">模拟买入</button>
            <button class="act sell" @click="openTrade('SELL')">模拟卖出</button>
            <button class="act alert" @click="openAlerts">提醒设置</button>
          </div>
          <!-- Bullets -->
          <div class="panel">
            <div class="panel-head"><span class="panel-title">弹幕</span><span class="panel-count">{{ bullets.length }}</span></div>
            <div class="barrage-form">
              <input v-model="barrageInput" class="bi" placeholder="发一条弹幕..." maxlength="60" @keyup.enter="doBarrage" />
              <div class="bdots"><span v-for="c in barrageColors" :key="c" class="bdot" :class="{active:barrageColor===c}" :style="'background:'+c" @click="barrageColor=c"></span></div>
              <button class="bsend" :disabled="barrageSending" @click="doBarrage">{{ barrageSending?'...':'发送' }}</button>
            </div>
            <div class="bullet-list" v-if="bullets.length">
              <div v-for="b in bullets" :key="b.id" class="brow"><span class="bd" :style="'background:'+b.color"></span><span class="bt">{{ b.content }}</span></div>
            </div>
            <div v-else class="empty-hint">暂无弹幕</div>
          </div>
        </div>

        <!-- Right: info -->
        <div class="detail-right">
          <div class="panel">
            <div class="panel-head"><span class="panel-title">基金信息</span></div>
            <div class="info-grid">
              <div class="ig"><span class="ik">基金代码</span><span class="iv">{{ fundCode }}</span></div>
              <div class="ig"><span class="ik">基金类型</span><span class="iv">{{ detail.fundType }}</span></div>
              <div class="ig"><span class="ik">基金公司</span><span class="iv">{{ detail.companyName }}</span></div>
              <div class="ig"><span class="ik">基金经理</span><span class="iv">{{ detail.managerName }}</span></div>
              <div class="ig"><span class="ik">风险等级</span><span class="iv">{{ detail.riskLevel }}</span></div>
              <div class="ig"><span class="ik">成立日期</span><span class="iv">{{ detail.inceptionDate }}</span></div>
              <div class="ig"><span class="ik">单位净值</span><span class="iv">{{ detail.unitNav?.toFixed(4) || '--' }}</span></div>
              <div class="ig"><span class="ik">累计净值</span><span class="iv">{{ detail.accumulatedNav?.toFixed(4) || '--' }}</span></div>
              <div class="ig"><span class="ik">业绩基准</span><span class="iv">{{ detail.benchmark || '--' }}</span></div>
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
.breadcrumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-text-tertiary); margin-bottom: 12px; }
.bc-link { cursor: pointer; color: var(--color-primary); }
.bc-sep { color: var(--color-border); }
.watch-btn { margin-left: auto; border: none; border-radius: 4px; padding: 4px 12px; font-size: 12px; font-weight: 600; background: var(--color-primary); color: #fff; }
.watch-btn.on { background: var(--color-bg-secondary); color: var(--color-text-secondary); }

.price-header { margin-bottom: 16px; }
.ph-main { display: flex; align-items: baseline; gap: 10px; }
.ph-val { font-size: 28px; font-weight: 800; font-variant-numeric: tabular-nums; }
.ph-pct { font-size: 16px; font-weight: 700; font-variant-numeric: tabular-nums; }
.ph-val.up, .ph-pct.up { color: var(--color-up); }
.ph-val.down, .ph-pct.down { color: var(--color-down); }
.ph-val.flat { color: var(--color-text-primary); }
.ph-sub { font-size: 12px; color: var(--color-text-tertiary); margin-top: 2px; }
.ph-est.up { color: var(--color-up); } .ph-est.down { color: var(--color-down); }

.detail-grid { display: grid; grid-template-columns: 1fr 300px; gap: 16px; }
.detail-left, .detail-right { display: flex; flex-direction: column; gap: 12px; }

.panel { background: var(--color-bg-card); border-radius: var(--radius-lg); box-shadow: 0 1px 3px var(--color-shadow); overflow: hidden; }
.panel-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--color-divider); }
.panel-title { font-size: 13px; font-weight: 700; color: var(--color-text-primary); }
.panel-count { font-size: 11px; color: var(--color-text-tertiary); }
.range-bar { display: inline-flex; gap: 1px; padding: 2px; background: var(--color-bg-secondary); border-radius: 4px; }
.range-btn { border: none; border-radius: 3px; padding: 4px 10px; background: transparent; color: var(--color-text-tertiary); font-size: 12px; font-weight: 500; }
.range-btn.active { background: var(--color-bg-card); color: var(--color-text-primary); font-weight: 600; box-shadow: 0 1px 2px var(--color-shadow); }
.chart-area { padding: 12px 14px; }

.action-bar { display: flex; gap: 8px; }
.act { flex: 1; border: none; border-radius: var(--radius); padding: 10px; font-size: 13px; font-weight: 700; }
.act.buy { background: var(--color-up-bg); color: var(--color-up); }
.act.sell { background: var(--color-down-bg); color: var(--color-down); }
.act.alert { background: var(--color-primary-light); color: var(--color-primary); }

.barrage-form { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-bottom: 1px solid var(--color-divider); }
.bi { flex: 1; height: 30px; border: 1px solid var(--color-border); border-radius: 4px; padding: 0 10px; font-size: 12px; color: var(--color-text-primary); background: var(--color-bg); outline: none; }
.bi:focus { border-color: var(--color-primary); }
.bdots { display: flex; gap: 3px; }
.bdot { width: 14px; height: 14px; border-radius: 7px; cursor: pointer; border: 2px solid transparent; }
.bdot.active { border-color: var(--color-text-primary); }
.bsend { border: none; border-radius: 4px; padding: 5px 12px; background: var(--color-primary); color: #fff; font-size: 12px; font-weight: 600; }
.bsend:disabled { opacity: 0.5; }
.bullet-list { max-height: 200px; overflow-y: auto; }
.brow { display: flex; align-items: center; gap: 8px; padding: 6px 14px; border-bottom: 1px solid var(--color-divider); font-size: 12px; }
.brow:last-child { border-bottom: none; }
.bd { width: 5px; height: 5px; border-radius: 3px; flex-shrink: 0; }
.bt { color: var(--color-text-primary); }
.empty-hint { padding: 16px; text-align: center; font-size: 12px; color: var(--color-text-tertiary); }

.info-grid { padding: 4px 0; }
.ig { display: flex; justify-content: space-between; padding: 8px 14px; border-bottom: 1px solid var(--color-divider); font-size: 12px; }
.ig:last-child { border-bottom: none; }
.ik { color: var(--color-text-tertiary); }
.iv { color: var(--color-text-primary); font-weight: 500; }

.mask { position: fixed; inset: 0; background: rgba(0,0,0,0.35); display: flex; align-items: center; justify-content: center; z-index: 200; }
.popup { width: 400px; background: var(--color-bg-card); border-radius: var(--radius-lg); padding: 20px; box-shadow: 0 8px 32px rgba(0,0,0,0.12); }
.ph { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.pt { font-size: 15px; font-weight: 700; color: var(--color-text-primary); }
.px { width: 28px; height: 28px; border: none; border-radius: 14px; background: var(--color-bg-secondary); color: var(--color-text-tertiary); font-size: 16px; display: flex; align-items: center; justify-content: center; }
.pb { margin-bottom: 14px; }
.fl { display: block; font-size: 12px; color: var(--color-text-tertiary); margin: 8px 0 4px; }
.fi { width: 100%; height: 36px; border: 1px solid var(--color-border); border-radius: var(--radius); padding: 0 10px; font-size: 13px; color: var(--color-text-primary); background: var(--color-bg); outline: none; box-sizing: border-box; }
.fi:focus { border-color: var(--color-primary); }
.fi.sm { width: 80px; }
.fs { height: 32px; border: 1px solid var(--color-border); border-radius: var(--radius); padding: 0 8px; font-size: 12px; color: var(--color-text-primary); background: var(--color-bg); outline: none; }
.fs.sm { width: 65px; }
.pc { width: 100%; height: 40px; border: none; border-radius: var(--radius); background: var(--color-primary); color: #fff; font-size: 14px; font-weight: 700; }
.pc:disabled { opacity: 0.5; }
.ar { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; }
.db { width: 28px; height: 28px; border: none; border-radius: var(--radius); background: var(--color-up-bg); color: var(--color-up); font-size: 14px; display: flex; align-items: center; justify-content: center; }
.ab { border: 1px dashed var(--color-border); border-radius: var(--radius); padding: 6px; width: 100%; background: transparent; color: var(--color-primary); font-size: 12px; }

.skel-full { height: 400px; border-radius: var(--radius-lg); background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>
