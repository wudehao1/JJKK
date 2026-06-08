<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
import Toast from '@/components/Toast.vue'
import { useTheme } from '@/composables/useTheme'

useTheme()
const route = useRoute()
const isLoginPage = computed(() => route.name === 'login')
</script>

<template>
  <div class="app-shell" :class="{ 'no-sidebar': isLoginPage }">
    <AppSidebar v-if="!isLoginPage" />
    <div class="app-main" :class="{ 'full-width': isLoginPage }">
      <AppHeader v-if="!isLoginPage" />
      <div class="app-content" :class="{ 'no-header': isLoginPage }">
        <router-view />
      </div>
    </div>
  </div>
  <Toast />
</template>

<style>
@import '@/styles/main.css';
</style>

<style scoped>
.app-shell { display: flex; min-height: 100vh; }
.app-shell.no-sidebar { display: block; }
.app-main { margin-left: 200px; flex: 1; min-width: 0; }
.app-main.full-width { margin-left: 0; }
.app-content { padding: 52px 24px 24px; min-height: 100vh; }
.app-content.no-header { padding: 0; }
</style>
