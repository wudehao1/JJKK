<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { listWatchlist, deleteWatchlist } from '@/api/user'
import { useToast } from '@/composables/useToast'
import FundCard from '@/components/FundCard.vue'
import type { WatchlistItem } from '@/types'

const auth = useAuthStore()
const toast = useToast()
const items = ref<WatchlistItem[]>([])
const loading = ref(true)

async function load() {
  if (!auth.isLoggedIn) return
  loading.value = true
  try { items.value = await listWatchlist(auth.userId) || [] } catch { /* silent */ } finally { loading.value = false }
}

async function remove(item: WatchlistItem) {
  try { await deleteWatchlist(auth.userId, item.id); items.value = items.value.filter(i => i.id !== item.id); toast.success('已移除') } catch { /* silent */ }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header"><h1 class="page-title">自选基金</h1></header>

    <template v-if="loading">
      <div class="skeleton-list"><div class="skeleton-item" v-for="i in 5" :key="i"></div></div>
    </template>

    <template v-else-if="items.length">
      <div class="fund-list">
        <div v-for="item in items" :key="item.id" class="watch-row">
          <FundCard :fund-code="item.fundCode" :fund-name="item.fundName" :unit-nav="item.unitNav" :daily-return-pct="item.dailyReturnPct" :estimate-return-pct="item.estimateReturnPct" :show-estimate="true" />
          <button class="remove-btn" @click="remove(item)">移除</button>
        </div>
      </div>
    </template>

    <div v-else class="empty">
      <div class="empty-icon">&#9733;</div>
      <div class="empty-text">还没有自选基金</div>
      <router-link to="/search" class="empty-link">去添加</router-link>
    </div>
  </div>
</template>

<style scoped>

.page-header { margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 800; color: var(--color-text-primary); }
.fund-list { background: var(--color-bg-card); border-radius: var(--radius-lg); box-shadow: 0 1px 3px var(--color-shadow); }
.watch-row { position: relative; }
.remove-btn {
  position: absolute; right: 0; top: 50%; transform: translateY(-50%);
  border: none; border-radius: 5px; padding: 3px 8px;
  background: var(--color-up-bg); color: var(--color-up); font-size: 11px;
  opacity: 0; transition: opacity 0.15s;
}
.watch-row:hover .remove-btn { opacity: 1; }
.empty { text-align: center; padding: 60px 20px; color: var(--color-text-tertiary); }
.empty-icon { font-size: 32px; margin-bottom: 8px; }
.empty-text { font-size: 14px; }
.empty-link { display: inline-block; margin-top: 10px; color: var(--color-primary); font-size: 13px; font-weight: 600; }
.skeleton-list { }
.skeleton-item {
  height: 52px; margin-bottom: 1px;
  background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%);
  background-size: 200% 100%; animation: shimmer 1.5s infinite;
}
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>
