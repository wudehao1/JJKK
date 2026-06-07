import { ref } from 'vue'

interface ToastItem {
  id: number
  message: string
  type: 'info' | 'success' | 'error'
  visible: boolean
}

const toasts = ref<ToastItem[]>([])
let nextId = 0

function show(message: string, type: ToastItem['type'] = 'info', duration = 2500) {
  const id = ++nextId
  const toast: ToastItem = { id, message, type, visible: true }
  toasts.value.push(toast)
  setTimeout(() => {
    toast.visible = false
    setTimeout(() => {
      toasts.value = toasts.value.filter(t => t.id !== id)
    }, 300)
  }, duration)
}

export function useToast() {
  return {
    toasts,
    info: (msg: string) => show(msg, 'info'),
    success: (msg: string) => show(msg, 'success'),
    error: (msg: string) => show(msg, 'error')
  }
}
