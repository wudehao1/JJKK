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

function fmtTime(t: string) {
  if (!t) return ''
  const d = new Date(t)
  return d.getFullYear() + '-' + (d.getMonth()+1).toString().padStart(2,'0') + '-' + d.getDate().toString().padStart(2,'0') + ' ' + d.getHours().toString().padStart(2,'0') + ':' + d.getMinutes().toString().padStart(2,'0')
}
</script>

<template>
  <div class="page">
    <div class="breadcrumb">
      <span class="bc-link" @click="router.push('/')">首页</span>
      <span class="bc-sep">/</span>
      <span class="bc-link" @click="router.push('/information')">资讯</span>
      <span class="bc-sep">/</span>
      <span>详情</span>
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
.breadcrumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-text-tertiary); margin-bottom: 16px; }
.bc-link { cursor: pointer; color: var(--color-primary); }
.bc-sep { color: var(--color-border); }
.loading-state, .empty-state { text-align: center; padding: 40px; color: var(--color-text-secondary); }
.article { max-width: 720px; }
.article-title { font-size: 22px; font-weight: 800; color: var(--color-text-primary); line-height: 1.4; margin: 0; }
.article-meta { display: flex; gap: 12px; margin-top: 10px; font-size: 13px; color: var(--color-text-tertiary); }
.article-body {
  margin-top: 20px; font-size: 15px; line-height: 1.8;
  color: var(--color-text-primary); word-break: break-word;
}
.article-body :deep(img) { max-width: 100%; border-radius: 8px; margin: 12px 0; }
.article-source-link {
  display: inline-block; margin-top: 20px; padding: 8px 16px;
  border-radius: var(--radius); background: var(--color-bg-secondary);
  color: var(--color-primary); font-size: 13px; text-decoration: none;
}
</style>
