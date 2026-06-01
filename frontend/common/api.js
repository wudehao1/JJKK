export const API_BASE_URL = 'http://192.168.31.100:8080/api'
export const WS_URL = 'ws://192.168.31.100:8080/api/ws/funds'
// export const API_BASE_URL = 'http://10.98.157.191:8080/api'
// export const WS_URL = 'ws://10.98.157.191:8080/api/ws/funds'
const USER_ID_KEY = 'jk_user_id'
const AUTH_TOKEN_KEY = 'jk_auth_token'

function normalizeUserId(value) {
  const num = Number(value)
  return Number.isInteger(num) && num > 0 ? num : 0
}

export function request(options) {
  const header = Object.assign({ 'content-type': 'application/json' }, options.header || {})
  const token = uni.getStorageSync(AUTH_TOKEN_KEY)
  if (options.auth !== false && token) {
    header.Authorization = 'Bearer ' + token
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url: API_BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      header,
      success: (res) => {
        const body = res.data || {}
        if (res.statusCode >= 200 && res.statusCode < 300 && body.success !== false) {
          resolve(body.data === undefined ? body : body.data)
          return
        }
        if ((res.statusCode === 401 || res.statusCode === 403) && options.auth !== false) {
          clearAuthStorage()
        }
        const message = body.message || '请求失败，请稍后重试'
        if (res.statusCode === 404 && options.auth !== false && String(message).indexOf('用户不存在') >= 0) {
          clearAuthStorage()
        }
        uni.showToast({ title: message, icon: 'none' })
        reject(new Error(message))
      },
      fail: (error) => {
        uni.showToast({ title: '无法连接后端服务', icon: 'none' })
        reject(error)
      }
    })
  })
}

export function ensureUser() {
  const cachedUserId = uni.getStorageSync(USER_ID_KEY)
  const token = uni.getStorageSync(AUTH_TOKEN_KEY)
  const validUserId = normalizeUserId(cachedUserId)
  if (validUserId > 0 && token) return Promise.resolve(validUserId)
  if (token && validUserId <= 0) {
    clearAuthStorage()
  }
  return loginWithWechat()
}

export function loginWithWechat() {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (loginRes) => {
        if (!loginRes.code) {
          const error = new Error('微信登录失败')
          uni.showToast({ title: error.message, icon: 'none' })
          reject(error)
          return
        }
        request({
          url: '/auth/wechat-login',
          method: 'POST',
          auth: false,
          data: {
            code: loginRes.code,
            nickname: '基金快看用户'
          }
        }).then((login) => {
          const userId = normalizeUserId(login.userId)
          if (userId <= 0) {
            clearAuthStorage()
            reject(new Error('用户登录信息无效，请重试'))
            return
          }
          uni.setStorageSync(USER_ID_KEY, userId)
          uni.setStorageSync(AUTH_TOKEN_KEY, login.token)
          resolve(userId)
        }).catch(reject)
      },
      fail: (error) => {
        uni.showToast({ title: '微信登录失败', icon: 'none' })
        reject(error)
      }
    })
  })
}

export function clearAuthStorage() {
  uni.removeStorageSync(USER_ID_KEY)
  uni.removeStorageSync(AUTH_TOKEN_KEY)
}

export function getMarketOverview() {
  return request({ url: '/market/overview' })
}

export function getMarketMinute(symbol) {
  return request({ url: '/market/indices/' + encodeURIComponent(symbol || '') + '/minute' })
}

export function getMarketHistory(symbol, range = '1m') {
  return request({ url: '/market/indices/' + encodeURIComponent(symbol || '') + '/history?range=' + encodeURIComponent(range) })
}

export function getFundMinute(fundCode) {
  return request({ url: '/funds/' + encodeURIComponent(fundCode || '') + '/minute' })
}

export function getFundHistory(fundCode, range = '1m') {
  return request({ url: '/funds/' + encodeURIComponent(fundCode || '') + '/history?range=' + encodeURIComponent(range) })
}

export function getFundDetail(fundCode) {
  return request({ url: '/funds/' + encodeURIComponent(fundCode || '') })
}

export function searchFunds(keyword, page = 1, size = 20) {
  const encoded = encodeURIComponent(keyword || '')
  return request({ url: '/funds?keyword=' + encoded + '&page=' + page + '&size=' + size })
}

export function searchOfficialFunds(keyword, limit = 20) {
  const encoded = encodeURIComponent(keyword || '')
  return request({ url: '/funds/official/search?keyword=' + encoded + '&limit=' + limit })
}

export function listWatchlist(userId) {
  return request({ url: '/users/' + userId + '/watchlist' })
}

export function addWatchlist(userId, data) {
  return request({ url: '/users/' + userId + '/watchlist', method: 'POST', data })
}

export function reorderWatchlist(userId, items) {
  return request({ url: '/users/' + userId + '/watchlist/order', method: 'PUT', data: { items } })
}

export function deleteWatchlist(userId, watchId) {
  return request({ url: '/users/' + userId + '/watchlist/' + watchId, method: 'DELETE' })
}

export function listHoldings(userId, options = {}) {
  const params = []
  if (options.fundCode) params.push('fundCode=' + encodeURIComponent(options.fundCode))
  if (options.accountId !== null && options.accountId !== undefined && options.accountId !== '') {
    params.push('accountId=' + encodeURIComponent(options.accountId))
  }
  if (options.sortBy) params.push('sortBy=' + encodeURIComponent(options.sortBy))
  if (options.sortDirection) params.push('sortDirection=' + encodeURIComponent(options.sortDirection))
  const query = params.length ? ('?' + params.join('&')) : ''
  return request({ url: '/users/' + userId + '/holdings' + query })
}

export function getHoldingSummary(userId) {
  return request({ url: '/users/' + userId + '/holdings/summary' })
}

export function getHoldingDashboard(userId, options = {}) {
  const params = []
  if (options.fundCode) params.push('fundCode=' + encodeURIComponent(options.fundCode))
  if (options.accountId !== null && options.accountId !== undefined && options.accountId !== '') {
    params.push('accountId=' + encodeURIComponent(options.accountId))
  }
  if (options.txnLimit !== null && options.txnLimit !== undefined && options.txnLimit !== '') {
    params.push('txnLimit=' + encodeURIComponent(options.txnLimit))
  }
  if (options.sortBy) params.push('sortBy=' + encodeURIComponent(options.sortBy))
  if (options.sortDirection) params.push('sortDirection=' + encodeURIComponent(options.sortDirection))
  const query = params.length ? ('?' + params.join('&')) : ''
  return request({ url: '/users/' + userId + '/holdings/dashboard' + query })
}

export function getHoldingTransactions(userId, options = {}) {
  const params = []
  if (options.fundCode) params.push('fundCode=' + encodeURIComponent(options.fundCode))
  if (options.accountId !== null && options.accountId !== undefined && options.accountId !== '') {
    params.push('accountId=' + encodeURIComponent(options.accountId))
  }
  if (options.limit !== null && options.limit !== undefined && options.limit !== '') {
    params.push('limit=' + encodeURIComponent(options.limit))
  }
  const query = params.length ? ('?' + params.join('&')) : ''
  return request({ url: '/users/' + userId + '/holdings/transactions' + query })
}

export function simulateHoldingTrade(userId, data) {
  return request({ url: '/users/' + userId + '/holdings/trades', method: 'POST', data })
}


export function connectFundSocket(onMessage, onOpen, onClose) {
  const token = uni.getStorageSync(AUTH_TOKEN_KEY)
  const socketTask = uni.connectSocket({
    url: WS_URL,
    header: token ? { Authorization: 'Bearer ' + token } : {}
  })
  const client = {
    send(payload) {
      const data = typeof payload === 'string' ? payload : payload.data
      if (socketTask && typeof socketTask.send === 'function') {
        socketTask.send({ data })
        return
      }
      uni.sendSocketMessage({ data })
    },
    close() {
      if (socketTask && typeof socketTask.close === 'function') {
        socketTask.close({})
        return
      }
      uni.closeSocket({})
    }
  }

  const handleOpen = () => { if (onOpen) onOpen(client) }
  const handleMessage = (event) => {
    try {
      onMessage(JSON.parse(event.data), client)
    } catch (error) {
      onMessage({ type: 'RAW', data: event.data }, client)
    }
  }
  const handleClose = () => { if (onClose) onClose() }

  if (socketTask && typeof socketTask.onOpen === 'function') {
    socketTask.onOpen(handleOpen)
    socketTask.onMessage(handleMessage)
    socketTask.onClose(handleClose)
    socketTask.onError(handleClose)
  } else {
    uni.onSocketOpen(handleOpen)
    uni.onSocketMessage(handleMessage)
    uni.onSocketClose(handleClose)
    uni.onSocketError(handleClose)
  }

  return client
}

export function money(value) {
  return Number(value || 0).toFixed(2)
}

export function percent(value) {
  if (value === null || value === undefined || value === '') return '--'
  const numberValue = Number(value)
  const sign = numberValue > 0 ? '+' : ''
  return sign + numberValue.toFixed(2) + '%'
}

export function navText(value) {
  if (value === null || value === undefined || value === '') return '--'
  return Number(value).toFixed(4)
}

export function valueClass(value) {
  const num = Number(value || 0)
  if (num > 0) return 'up'
  if (num < 0) return 'down'
  return 'flat'
}


export function getInformationList(page = 1, size = 6, importantOnly = false) {
  return request({ url: '/information?page=' + page + '&size=' + size + '&importantOnly=' + (importantOnly ? 'true' : 'false') })
}

export function getInformationDetail(id) {
  return request({ url: '/information/' + encodeURIComponent(id || '') })
}

export function getCurrentUser() {
  return ensureUser().then((userId) => request({ url: '/users/' + userId }))
}

export function updateUserProfile(data) {
  return ensureUser().then((userId) => request({ url: '/users/' + userId + '/profile', method: 'PUT', data }))
}


export function uploadUserAvatar(userId, filePath) {
  const token = uni.getStorageSync(AUTH_TOKEN_KEY)
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: API_BASE_URL + '/users/' + userId + '/avatar',
      filePath,
      name: 'file',
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: (res) => {
        let body = {}
        try {
          body = typeof res.data === 'string' ? JSON.parse(res.data || '{}') : (res.data || {})
        } catch (error) {
          body = {}
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body.success !== false) {
          resolve(body.data === undefined ? body : body.data)
          return
        }
        const message = body.message || '头像上传失败'
        uni.showToast({ title: message, icon: 'none' })
        reject(new Error(message))
      },
      fail: (error) => {
        uni.showToast({ title: '头像上传失败', icon: 'none' })
        reject(error)
      }
    })
  })
}


export function submitFeedback(data) {
  return request({ url: '/feedback', method: 'POST', data })
}


export function getMarketFundBreadth() {
  return request({ url: '/market/fund-breadth' })
}

export function getSectorRankings(limit = 10) {
  return request({ url: '/market/sector-rankings?limit=' + limit })
}


export function getFundRankings(limit = 10) {
  return request({ url: '/market/fund-rankings?limit=' + limit })
}
