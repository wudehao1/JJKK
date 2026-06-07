<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { listWatchlist, deleteWatchlist } from '@/api/user'
import FundCard from '@/components/FundCard.vue'
import type { WatchlistItem } from '@/types'

const auth = useAuthStore()
const items = ref<WatchlistItem[]>([])
const loading = ref(true)

async function load() {
  if (!auth.isLoggedIn) return
  loading.value = true
  try {
    items.value = await listWatchlist(auth.userId) || []
  } catch { /* silent */ } finally {
    loading.value = false
  }
}

async function remove(item: WatchlistItem) {
  if (!confirm('确认移除 ' + item.fundName + ' ？')) return
  try {
    await deleteWatchlist(auth.userId, item.id)
    items.value = items.value.filter(i => i.id !== item.id)
  } catch { /* silent */ }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h1 class="page-title">自选基金</h1>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <div v-else-if="items.length === 0" class="empty-state">
      <div class="empty-icon">&#9733;</div>
      <div>还没有自选基金</div>
      <router-link to="/search" class="empty-link">去添加</router-link>
    </div>

    <div v-else class="fund-list">
      <div v-for="item in items" :key="item.id" class="watch-item">
        <FundCard
          :fund-code="item.fundCode"
          :fund-name="item.fundName"
          :unit-nav="item.unitNav"
          :daily-return-pct="item.dailyReturnPct"
          :estimate-return-pct="item.estimateReturnPct"
          :show-estimate="true"
        />
        <button class="remove-btn" @click.stop="remove(item)">移除</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { padding-bottom: 72px; }
.page-header { padding: 16px 16px 8px; }
.page-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); margin: 0; }
.loading-state, .empty-state { text-align: center; padding: 60px 20px; color: var(--color-text-secondary); }
.empty-icon { font-size: 36px; margin-bottom: 8px; }
.empty-link { color: var(--color-primary); text-decoration: none; font-size: 14px; margin-top: 8px; display: inline-block; }
.watch-item { position: relative; }
.remove-btn {
  position: absolute; right: 12px; top: 50%; transform: translateY(-50%);
  border: none; border-radius: 6px; padding: 4px 10px;
  background: #FEE2E2; color: #DC2626; font-size: 12px; cursor: pointer;
  opacity: 0; transition: opacity 0.2s;
}
.watch-item:hover .remove-btn { opacity: 1; }
</style>
