import http from './request'
import type { WatchlistItem, HoldingDashboard, UserProfile, InformationPage, AlertRule } from '@/types'

export function getUserProfile(userId: number): Promise<UserProfile> {
  return http.get('/users/' + userId)
}

export function updateUserProfile(userId: number, data: Partial<UserProfile>): Promise<void> {
  return http.put('/users/' + userId + '/profile', data)
}

export function listWatchlist(userId: number): Promise<WatchlistItem[]> {
  return http.get('/users/' + userId + '/watchlist')
}

export function addWatchlist(userId: number, data: { fundCode: string }): Promise<unknown> {
  return http.post('/users/' + userId + '/watchlist', data)
}

export function deleteWatchlist(userId: number, watchId: number): Promise<void> {
  return http.delete('/users/' + userId + '/watchlist/' + watchId)
}

export function getHoldingDashboard(userId: number, options?: { sortBy?: string; sortDirection?: string }): Promise<HoldingDashboard> {
  return http.get('/users/' + userId + '/holdings/dashboard', { params: options || {} })
}

export function getHoldingSummary(userId: number): Promise<unknown> {
  return http.get('/users/' + userId + '/holdings/summary')
}

export function simulateHoldingTrade(userId: number, data: unknown): Promise<unknown> {
  return http.post('/users/' + userId + '/holdings/trades', data)
}

export function getFundAlertSettings(userId: number, fundCode: string): Promise<AlertRule[]> {
  return http.get('/users/' + userId + '/fund-alerts/' + encodeURIComponent(fundCode))
}

export function saveFundAlertSettings(userId: number, fundCode: string, data: unknown): Promise<void> {
  return http.put('/users/' + userId + '/fund-alerts/' + encodeURIComponent(fundCode), data)
}

export function getInformationList(page = 1, size = 6, importantOnly = false): Promise<InformationPage> {
  return http.get('/information', { params: { page, size, importantOnly: importantOnly ? 'true' : 'false' } })
}

export function getInformationDetail(id: number): Promise<unknown> {
  return http.get('/information/' + id)
}

export function getProfitAnalysis(userId: number, options?: { range?: string; accountId?: number }): Promise<unknown> {
  return http.get('/users/' + userId + '/profit-analysis', { params: options || {} })
}

export function submitFeedback(data: { content: string }): Promise<void> {
  return http.post('/feedback', data)
}