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
    const list = res?.items || []
    if (append) {
      items.value = items.value.concat(list)
    } else {
      items.value = list
    }
    hasMore.value = list.length >= 10
  } catch { /* silent */ } finally {
    loading.value = false
  }
}

function loadMore() {
  if (!hasMore.value || loading.value) return
  page.value++
  load(true)
}

function toggleImportant() {
  importantOnly.value = !importantOnly.value
  load()
}

function goDetail(id: number) {
  router.push('/information/' + id)
}

function fmtTime(t: string) {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const diff = now.getTime() - d.getTime()
  if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前'
  return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0')
}

onMounted(() => load())
</script>

<template>
  <div class="page info-page">
    <div class="page-header">
      <h1 class="page-title">资讯</h1>
      <button class="filter-btn" :class="{ active: importantOnly }" @click="toggleImportant">
        {{ importantOnly ? '全部' : '重要' }}
      </button>
    </div>

    <div v-if="loading && !items.length" class="loading-state">加载中...</div>

    <div class="info-list">
      <div v-for="item in items" :key="item.id" class="info-item" @click="goDetail(item.id)">
        <div class="info-content">
          <div class="info-title" :class="{ important: item.importance === 'IMPORTANT' }">{{ item.title }}</div>
          <div class="info-summary" v-if="item.summary">{{ item.summary }}</div>
          <div class="info-meta">
            <span class="info-source">{{ item.sourceName }}</span>
            <span class="info-time">{{ fmtTime(item.publishTime) }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="hasMore && items.length" class="load-more">
      <button class="load-more-btn" :disabled="loading" @click="loadMore">
        {{ loading ? '加载中...' : '加载更多' }}
      </button>
    </div>

    <div v-if="!loading && items.length === 0" class="empty-state">暂无资讯</div>
  </div>
</template>

<style scoped>
.info-page { padding-bottom: 72px; }
.page-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 16px 8px;
}
.page-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); margin: 0; }
.filter-btn {
  border: none; border-radius: 6px; padding: 6px 14px;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
  font-size: 12px; cursor: pointer;
}
.filter-btn.active { background: var(--color-primary); color: #fff; }
.loading-state, .empty-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
.info-list { background: var(--color-bg-card); margin: 8px 16px; border-radius: 10px; overflow: hidden; }
.info-item {
  padding: 14px 16px; border-bottom: 1px solid var(--color-border);
  cursor: pointer;
}
.info-item:hover { background: var(--color-bg-hover); }
.info-item:last-child { border-bottom: none; }
.info-title { font-size: 14px; font-weight: 600; color: var(--color-text-primary); line-height: 1.5; }
.info-title.important { color: var(--color-up); }
.info-summary {
  font-size: 13px; color: var(--color-text-secondary); margin-top: 6px;
  line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2;
  -webkit-box-orient: vertical; overflow: hidden;
}
.info-meta { display: flex; gap: 10px; margin-top: 8px; font-size: 12px; color: var(--color-text-tertiary); }
.load-more { text-align: center; padding: 16px; }
.load-more-btn {
  border: none; border-radius: 8px; padding: 10px 24px;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
  font-size: 13px; cursor: pointer;
}
.load-more-btn:disabled { opacity: 0.5; }
</style>
