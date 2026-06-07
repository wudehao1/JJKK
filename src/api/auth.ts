import http from './request'

export interface LoginResult {
  token: string
  userId: number
  displayUserId: string
  nickname: string
  avatarUrl: string
  expiresAt: string
}

/** 发送短信验证码 */
export function sendSmsCode(phone: string): Promise<void> {
  return http.post('/auth/sms-code', { phone })
}

/** 手机号 + 验证码登录 */
export function loginByPhone(phone: string, code: string): Promise<LoginResult> {
  return http.post('/auth/phone-login', { phone, code })
}

/** 微信网页 OAuth 登录（前端拿到 OAuth code 后调用） */
export function loginByWechatWeb(code: string, nickname?: string): Promise<LoginResult> {
  return http.post('/auth/wechat-web-login', { code, nickname })
}

/** 微信小程序登录（Web 端不直接使用，保留兼容） */
export function loginByWechat(code: string, nickname?: string): Promise<LoginResult> {
  return http.post('/auth/wechat-login', { code, nickname })
}

/** 开发环境快速登录 */
export function devLogin(nickname?: string): Promise<LoginResult> {
  const openid = 'web-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
  return http.post('/auth/wechat-login', {
    code: 'dev:' + openid,
    nickname: nickname || 'Web用户'
  })
}
