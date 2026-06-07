<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts } = useToast()
</script>

<template>
  <div class="toast-container">
    <TransitionGroup name="toast">
      <div
        v-for="t in toasts"
        :key="t.id"
        class="toast-item"
        :class="t.type"
        :style="t.visible ? '' : 'opacity:0;transform:translateY(-10px)'"
      >{{ t.message }}</div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-container {
  position: fixed; top: 16px; left: 50%; transform: translateX(-50%);
  z-index: 9999; display: flex; flex-direction: column; align-items: center; gap: 8px;
  pointer-events: none;
}
.toast-item {
  padding: 10px 20px; border-radius: 8px; font-size: 13px; font-weight: 600;
  color: #fff; pointer-events: auto; transition: opacity 0.3s, transform 0.3s;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15); max-width: 320px; text-align: center;
}
.toast-item.info { background: #3B82F6; }
.toast-item.success { background: #16A34A; }
.toast-item.error { background: #DC2626; }
.toast-enter-active { transition: opacity 0.3s, transform 0.3s; }
.toast-leave-active { transition: opacity 0.3s, transform 0.3s; }
.toast-enter-from { opacity: 0; transform: translateY(-10px); }
.toast-leave-to { opacity: 0; transform: translateY(-10px); }
</style>
