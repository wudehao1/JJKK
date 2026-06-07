import http from './request'

export interface LoginResult {
  token: string
  userId: number
  displayUserId: string
  nickname: string
  expiresAt: string
}

/**
 * 微信登录（小程序专用，Web端不直接使用）
 */
export function loginByWechat(code: string, nickname?: string): Promise<LoginResult> {
  return http.post('/auth/wechat-login', { code, nickname })
}

/**
 * Web端开发环境快速登录
 * 后端 allow-dev-login=true 时，code 以 dev: 开头即可直接登录
 */
export function devLogin(nickname?: string): Promise<LoginResult> {
  const openid = 'web-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
  return http.post('/auth/wechat-login', {
    code: 'dev:' + openid,
    nickname: nickname || 'Web用户'
  })
}
