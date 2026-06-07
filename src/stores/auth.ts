import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getAuth, setAuth, clearAuth, getNickname } from '@/api/request'
import { devLogin, loginByPhone } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const userId = ref(getAuth()?.userId || 0)
  const token = ref(getAuth()?.token || '')
  const nickname = ref(getNickname())

  const isLoggedIn = computed(() => userId.value > 0 && !!token.value)

  async function login(phone: string, code: string) {
    const result = await loginByPhone(phone, code)
    userId.value = result.userId
    token.value = result.token
    nickname.value = result.nickname || '基金快看用户'
    setAuth(result.token, result.userId, nickname.value)
  }

  async function quickLogin() {
    const result = await devLogin()
    userId.value = result.userId
    token.value = result.token
    nickname.value = result.nickname || 'Web用户'
    setAuth(result.token, result.userId, nickname.value)
  }

  function logout() {
    userId.value = 0
    token.value = ''
    nickname.value = ''
    clearAuth()
  }

  return { userId, token, nickname, isLoggedIn, login, quickLogin, logout }
})
