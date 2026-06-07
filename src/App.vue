<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import BottomNav from '@/components/BottomNav.vue'
import { useTheme } from '@/composables/useTheme'

useTheme()
const route = useRoute()
const showNav = computed(() => !!route.meta.tab)
</script>

<template>
  <router-view />
  <BottomNav v-if="showNav" />
</template>

<style>
@import '@/styles/main.css';
</style>
