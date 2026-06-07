import axios from 'axios'
import type { ApiResponse } from '@/types'

const TOKEN_KEY = 'jk_auth_token'
const USER_ID_KEY = 'jk_user_id'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse
    if (body.success === false) {
      const msg = body.message || '请求失败'
      return Promise.reject(new Error(msg))
    }
    return body.data === undefined ? body : body.data
  },
  (error) => {
    if (error.response) {
      const status = error.response.status
      if (status === 401 || status === 403) {
        clearAuth()
        window.location.href = '/login'
      }
      const msg = error.response.data?.message || '请求失败'
      return Promise.reject(new Error(msg))
    }
    return Promise.reject(new Error('网络连接失败'))
  }
)

export function getAuth(): { token: string; userId: number } | null {
  const token = localStorage.getItem(TOKEN_KEY)
  const userId = Number(localStorage.getItem(USER_ID_KEY))
  return token && userId > 0 ? { token, userId } : null
}

export function setAuth(token: string, userId: number, nickname: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_ID_KEY, String(userId))
  localStorage.setItem('jk_nickname', nickname)
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_ID_KEY)
  localStorage.removeItem('jk_nickname')
}

export function getNickname(): string {
  return localStorage.getItem('jk_nickname') || '基金快看用户'
}

export default http
