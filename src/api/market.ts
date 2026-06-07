import http from './request'
import type { MarketOverview, SectorRanking, FundRanking } from '@/types'

export function getMarketOverview(): Promise<MarketOverview> {
  return http.get('/market/overview')
}

export function getMarketMinute(symbol: string): Promise<unknown> {
  return http.get('/market/indices/' + encodeURIComponent(symbol) + '/minute')
}

export function getMarketHistory(symbol: string, range = '1m'): Promise<unknown> {
  return http.get('/market/indices/' + encodeURIComponent(symbol) + '/history', { params: { range } })
}

export function getSectorRankings(limit = 10): Promise<SectorRanking[]> {
  return http.get('/market/sector-rankings', { params: { limit } })
}

export function getFundRankings(limit = 10): Promise<FundRanking[]> {
  return http.get('/market/fund-rankings', { params: { limit } })
}

export function getMarketFundBreadth(): Promise<unknown> {
  return http.get('/market/fund-breadth')
}
