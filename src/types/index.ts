// API response wrapper
export interface ApiResponse<T = unknown> {
  success: boolean
  data: T
  message?: string
}

// Auth
export interface LoginRequest {
  phone: string
  code: string
}

export interface LoginResult {
  userId: number
  token: string
  nickname: string
}

// Market
export interface MarketIndex {
  symbol: string
  name: string
  latest: number
  change: number
  changePct: number
  volume?: number
  amount?: number
}

export interface MarketOverview {
  indices: MarketIndex[]
  riseCount: number
  fallCount: number
  updateTime: string
}

export interface SectorRanking {
  sectorCode: string
  sectorName: string
  avgReturnPct: number
  topFund: string
}

export interface FundRanking {
  fundCode: string
  fundName: string
  estimateReturnPct: number
  unitNav: number
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

export interface FundHistoryPoint {
  date: string
  nav: number
  returnPct: number
}

export interface FundMinutePoint {
  time: string
  estimateNav: number
  estimateReturnPct: number
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
  imageUrl?: string
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
  phone?: string
  createdAt: string
}

// Chart
export interface ChartPoint {
  x: number
  y: number
  label: string
  value: number
}

// Profit analysis
export interface ProfitAnalysisData {
  totalProfit: number
  totalProfitPct: number
  dailyData: { date: string; profit: number }[]
}
