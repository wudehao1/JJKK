import http from './request'
import type { FundDetail, FundSearchPage, BulletCommentPage } from '@/types'

export function getFundDetail(fundCode: string): Promise<FundDetail> {
  return http.get('/funds/' + encodeURIComponent(fundCode))
}

export function getFundHistory(fundCode: string, range = '1m'): Promise<unknown> {
  return http.get('/funds/' + encodeURIComponent(fundCode) + '/history', { params: { range } })
}

export function getFundMinute(fundCode: string): Promise<unknown> {
  return http.get('/funds/' + encodeURIComponent(fundCode) + '/minute')
}

export function searchFunds(keyword: string, page = 1, size = 20): Promise<FundSearchPage> {
  return http.get('/funds', { params: { keyword, page, size } })
}

export function searchOfficialFunds(keyword: string, limit = 20): Promise<unknown> {
  return http.get('/funds/official/search', { params: { keyword, limit } })
}

export function getFundBullets(fundCode: string): Promise<BulletCommentPage> {
  return http.get('/funds/' + encodeURIComponent(fundCode) + '/bullet-comments')
}

export function sendFundBullet(fundCode: string, data: { content: string; color: string }): Promise<BulletCommentPage> {
  return http.post('/funds/' + encodeURIComponent(fundCode) + '/bullet-comments', data)
}
