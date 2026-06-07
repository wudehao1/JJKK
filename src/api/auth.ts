import http from './request'
import type { LoginResult } from '@/types'

export function loginByPhone(phone: string, code: string): Promise<LoginResult> {
  return http.post('/auth/phone-login', { phone, code })
}

export function sendSmsCode(phone: string): Promise<void> {
  return http.post('/auth/sms-code', { phone })
}

export function devLogin(nickname?: string): Promise<LoginResult> {
  return http.post('/auth/dev-login', { nickname: nickname || 'Web用户' })
}
