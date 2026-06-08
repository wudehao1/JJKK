// API response wrapper
export interface ApiResponse<T = unknown> {
  success: boolean
  data: T
  message?: string
}

// Auth
export interface LoginResult {
  token: string
  userId: number
  displayUserId: string
  nickname: string
  avatarUrl: string
  expiresAt: string
}

// Market - matches backend MarketDtos exactly
export interface IndexQuote {
  market: string
  symbol: string
  name: string
  lastPrice: number
  changeAmount: number
  changePct: number
  turnover: number
  dataLagSeconds: number
  quoteTime: string
}

export interface MarketOverview {
  tradingDay: string
  updatedAt: string
  dataStatus: string
  indices: IndexQuote[]
  // breadth fields from FundBreadthResponse or live snapshot
  upCount?: number
  downCount?: number
  riseCount?: number
  fallCount?: number
}

export interface SectorRanking {
  code: string
  name: string
  latestValue: number
  changeAmount: number
  changePct: number
  mainNetInflow: number
  direction: string
}

export interface FundRanking {
  fundCode: string
  fundName: string
  sectorName: string
  latestNavDate: string
  latestUnitNav: number
  returnPct: number
  dataType: string
}

// Fund
export interface FundDetail {
  fundCode: string
  fundName: string
  fundType: string
  unitNav: number
  accumulatedNav: number
  dailyReturnPct: number
  navDate: string
  estimateNav: number
  estimateReturnPct: number
  estimateTime: string
  riskLevel: string
  companyName: string
  managerName: string
  inceptionDate: string
  benchmark: string
}

export interface FundSearchResult {
  fundCode: string
  fundName: string
  fundType: string
  companyName: string
  unitNav: number
  dailyReturnPct: number
}

export interface FundSearchPage {
  items: FundSearchResult[]
  total: number
  page: number
  size: number
}

// Watchlist
export interface WatchlistItem {
  id: number
  fundCode: string
  fundName: string
  fundType: string
  unitNav: number
  dailyReturnPct: number
  estimateReturnPct: number
  alertEnabled: boolean
  displayOrder: number
}

// Holdings
export interface HoldingSummary {
  totalCost: number
  totalMarketValue: number
  totalProfitLoss: number
  totalProfitLossPct: number
  holdingCount: number
}

export interface HoldingItem {
  id: number
  fundCode: string
  fundName: string
  holdingShare: number
  costAmount: number
  confirmedAmount: number
  profitLossAmount: number
  profitLossPct: number
  lastConfirmedNav: number
  lastConfirmedNavDate: string
}

export interface HoldingDashboard {
  summary: HoldingSummary
  holdings: HoldingItem[]
}

// Bullet comments
export interface BulletComment {
  id: number
  content: string
  color: string
  createdAt: string
  expiresAt: string
}

export interface BulletCommentPage {
  fundCode: string
  items: BulletComment[]
}

// Information
export interface InformationItem {
  id: number
  title: string
  summary: string
  sourceName: string
  publishTime: string
  importance: string
}

export interface InformationPage {
  items: InformationItem[]
  total: number
  page: number
  size: number
}

// Alert rule
export interface AlertRule {
  id: number
  fundCode: string
  ruleType: string
  compareOp: string
  thresholdValue: number
  enabled: boolean
  remindMode: string
}

// User profile
export interface UserProfile {
  id: number
  nickname: string
  avatarUrl: string
  createdAt: string
}
