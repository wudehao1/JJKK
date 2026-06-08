<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getInformationList } from '@/api/user'
import type { InformationItem } from '@/types'

const router = useRouter()
const items = ref<InformationItem[]>([])
const loading = ref(true)
const page = ref(1)
const hasMore = ref(true)
const importantOnly = ref(false)

async function load(append = false) {
  if (!append) { page.value = 1; loading.value = true }
  try {
    const res = await getInformationList(page.value, 10, importantOnly.value)
    items.value = append ? items.value.concat(res?.items || []) : (res?.items || [])
    hasMore.value = (res?.items || []).length >= 10
  } catch { /* silent */ } finally { loading.value = false }
}

function loadMore() { if (!hasMore.value || loading.value) return; page.value++; load(true) }
function toggleImportant() { importantOnly.value = !importantOnly.value; load() }
function goDetail(id: number) { router.push('/information/' + id) }

function fmtTime(t: string) {
  if (!t) return ''
  const d = new Date(t), now = new Date(), diff = now.getTime() - d.getTime()
  if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前'
  return (d.getMonth() + 1) + '/' + d.getDate()
}

onMounted(() => load())
</script>

<template>
  <div class="page">
    <header class="page-header">
      <h1 class="page-title">资讯</h1>
      <button class="filter-btn" :class="{ active: importantOnly }" @click="toggleImportant">{{ importantOnly ? '全部' : '重要' }}</button>
    </header>

    <template v-if="loading && !items.length">
      <div class="skeleton-list"><div class="skeleton-item" v-for="i in 4" :key="i"></div></div>
    </template>

    <div class="info-list" v-else-if="items.length">
      <div v-for="item in items" :key="item.id" class="info-item" @click="goDetail(item.id)">
        <div class="info-title" :class="{ important: item.importance === 'IMPORTANT' }">{{ item.title }}</div>
        <div class="info-summary" v-if="item.summary">{{ item.summary }}</div>
        <div class="info-meta">
          <span>{{ item.sourceName }}</span>
          <span>{{ fmtTime(item.publishTime) }}</span>
        </div>
      </div>
    </div>

    <div v-else class="empty">暂无资讯</div>

    <div v-if="hasMore && items.length" class="more">
      <button class="more-btn" :disabled="loading" @click="loadMore">{{ loading ? '...' : '加载更多' }}</button>
    </div>
  </div>
</template>

<style scoped>

.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 800; color: var(--color-text-primary); }
.filter-btn { border: none; border-radius: 5px; padding: 5px 12px; background: var(--color-bg-secondary); color: var(--color-text-secondary); font-size: 12px; font-weight: 500; }
.filter-btn.active { background: var(--color-primary); color: #fff; }
.empty { text-align: center; padding: 40px; color: var(--color-text-tertiary); font-size: 13px; }
.info-list { background: var(--color-bg-card); border-radius: var(--radius-lg); overflow: hidden; box-shadow: 0 1px 3px var(--color-shadow); }
.info-item { padding: 14px 16px; border-bottom: 1px solid var(--color-divider); cursor: pointer; }
.info-item:last-child { border-bottom: none; }
.info-item:hover { background: var(--color-bg-hover); }
.info-title { font-size: 14px; font-weight: 600; color: var(--color-text-primary); line-height: 1.5; }
.info-title.important { color: var(--color-up); }
.info-summary { font-size: 13px; color: var(--color-text-secondary); margin-top: 4px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.info-meta { display: flex; gap: 10px; margin-top: 6px; font-size: 11px; color: var(--color-text-tertiary); }
.more { text-align: center; padding: 16px; }
.more-btn { border: none; border-radius: var(--radius); padding: 8px 20px; background: var(--color-bg-secondary); color: var(--color-text-secondary); font-size: 12px; }
.more-btn:disabled { opacity: 0.5; }
.skeleton-list { }
.skeleton-item { height: 80px; border-radius: var(--radius-lg); margin: 8px 0; background: linear-gradient(90deg, var(--color-bg-secondary) 25%, var(--color-bg-card) 50%, var(--color-bg-secondary) 75%); background-size: 200% 100%; animation: shimmer 1.5s infinite; }
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>
