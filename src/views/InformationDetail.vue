<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getInformationDetail } from '@/api/user'

const route = useRoute()
const router = useRouter()
const detail = ref<any>(null)
const loading = ref(true)

onMounted(async () => {
  const id = Number(route.params.id)
  if (!id) { router.back(); return }
  try {
    detail.value = await getInformationDetail(id)
  } catch { /* silent */ } finally {
    loading.value = false
  }
})

function goBack() { router.back() }
function fmtTime(t: string) {
  if (!t) return ''
  const d = new Date(t)
  return d.getFullYear() + '-' + (d.getMonth()+1).toString().padStart(2,'0') + '-' + d.getDate().toString().padStart(2,'0') + ' ' + d.getHours().toString().padStart(2,'0') + ':' + d.getMinutes().toString().padStart(2,'0')
}
</script>

<template>
  <div class="page">
    <div class="detail-header">
      <button class="back-btn" @click="goBack">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5m7-7l-7 7 7 7"/></svg>
      </button>
      <div class="header-title">资讯详情</div>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <article v-else-if="detail" class="article">
      <h1 class="article-title">{{ detail.title }}</h1>
      <div class="article-meta">
        <span>{{ detail.sourceName }}</span>
        <span>{{ fmtTime(detail.publishTime) }}</span>
      </div>
      <div class="article-body" v-html="detail.content || detail.summary || '暂无内容'"></div>
      <a v-if="detail.sourceUrl" :href="detail.sourceUrl" target="_blank" class="article-source-link">查看原文</a>
    </article>

    <div v-else class="empty-state">资讯不存在</div>
  </div>
</template>

<style scoped>
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
.header-title { font-size: 17px; font-weight: 700; color: var(--color-text-primary); }
.loading-state, .empty-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
.article { padding: 20px 16px; }
.article-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); line-height: 1.4; margin: 0; }
.article-meta { display: flex; gap: 12px; margin-top: 10px; font-size: 13px; color: var(--color-text-tertiary); }
.article-body {
  margin-top: 20px; font-size: 15px; line-height: 1.8;
  color: var(--color-text-primary); word-break: break-word;
}
.article-body :deep(img) { max-width: 100%; border-radius: 8px; margin: 12px 0; }
.article-source-link {
  display: inline-block; margin-top: 20px; padding: 8px 16px;
  border-radius: 8px; background: var(--color-bg-secondary);
  color: var(--color-primary); font-size: 13px; text-decoration: none;
}
</style>
