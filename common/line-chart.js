function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : null
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}

function round(value) {
  return Math.round(value * 100) / 100
}

function chartArea(width, height) {
  const padding = { top: 20, right: 14, bottom: 24, left: 14 }
  return {
    left: padding.left,
    right: padding.right,
    top: padding.top,
    bottom: padding.bottom,
    width: Math.max(1, width - padding.left - padding.right),
    height: Math.max(1, height - padding.top - padding.bottom)
  }
}

function minuteOfDay(text) {
  if (!text) return null
  const value = String(text).replace('T', ' ')
  const match = value.match(/(\d{2}):(\d{2})/)
  if (!match) return null
  return Number(match[1]) * 60 + Number(match[2])
}

function continuousMinuteIndex(minute, start, end) {
  let value = minute
  let close = end
  if (end < start) {
    close = end + 24 * 60
    if (value < start) value += 24 * 60
  }
  if (value <= start) return { index: 0, total: close - start }
  if (value >= close) return { index: close - start, total: close - start }
  return { index: value - start, total: close - start }
}

function shfeMinuteIndex(minute) {
  if (minute >= 21 * 60) return { index: minute - 21 * 60, total: 572 }
  if (minute <= 2 * 60 + 30) return { index: 180 + minute, total: 572 }
  if (minute < 9 * 60) return { index: 330, total: 572 }
  if (minute <= 11 * 60 + 30) return { index: 331 + minute - 9 * 60, total: 572 }
  if (minute < 13 * 60 + 30) return { index: 481, total: 572 }
  if (minute <= 15 * 60) return { index: 482 + minute - 13 * 60 - 30, total: 572 }
  return { index: 572, total: 572 }
}

function sgeMinuteIndex(minute) {
  if (minute >= 20 * 60) return { index: minute - 20 * 60, total: 662 }
  if (minute <= 2 * 60 + 30) return { index: 240 + minute, total: 662 }
  if (minute < 9 * 60) return { index: 390, total: 662 }
  if (minute <= 11 * 60 + 30) return { index: 391 + minute - 9 * 60, total: 662 }
  if (minute < 13 * 60 + 30) return { index: 541, total: 662 }
  if (minute <= 15 * 60 + 30) return { index: 542 + minute - 13 * 60 - 30, total: 662 }
  return { index: 662, total: 662 }
}

function tradingMinuteIndex(text, options = {}) {
  const minute = minuteOfDay(text)
  if (minute === null) return null
  const market = options.market || ''
  if (market === 'HKEX') {
    const morningStart = 9 * 60 + 30
    const morningEnd = 12 * 60
    const afternoonStart = 13 * 60
    const close = 16 * 60
    if (minute <= morningStart) return { index: 0, total: 331 }
    if (minute <= morningEnd) return { index: minute - morningStart, total: 331 }
    if (minute < afternoonStart) return { index: 150, total: 331 }
    if (minute <= close) return { index: 151 + minute - afternoonStart, total: 331 }
    return { index: 331, total: 331 }
  }
  if (market === 'NYSE' || market === 'NASDAQ' || market === 'AMEX') {
    return continuousMinuteIndex(minute, 9 * 60 + 30, 16 * 60)
  }
  if (market === 'SHFE') {
    return shfeMinuteIndex(minute)
  }
  if (options.symbol === 'AU9999') {
    return sgeMinuteIndex(minute)
  }
  if (market === 'FX' || market === 'OTHER') {
    return continuousMinuteIndex(minute, 6 * 60, 5 * 60)
  }
  const morningStart = 9 * 60 + 30
  const morningEnd = 11 * 60 + 30
  const afternoonStart = 13 * 60
  const close = 15 * 60
  if (minute <= morningStart) return { index: 0, total: 241 }
  if (minute <= morningEnd) return { index: minute - morningStart, total: 241 }
  if (minute < afternoonStart) return { index: 120, total: 241 }
  if (minute <= close) return { index: 121 + minute - afternoonStart, total: 241 }
  return { index: 241, total: 241 }
}

function useTradingAxis(options = {}) {
  return options.fixedTimeAxis !== false && (options.today || options.symmetric)
}

function xForPoint(item, index, total, area, options = {}) {
  if (useTradingAxis(options)) {
    const minuteIndex = tradingMinuteIndex(item.time, options)
    if (minuteIndex !== null) {
      const range = options.timeAxisRange || { min: 0, max: minuteIndex.total }
      const rangeWidth = Math.max(1, range.max - range.min)
      return area.left + area.width * clamp(minuteIndex.index - range.min, 0, rangeWidth) / rangeWidth
    }
  }
  return total === 1 ? area.left + area.width / 2 : area.left + area.width * index / (total - 1)
}

export function normalizeLinePoints(rawPoints, options = {}) {
  const points = (rawPoints || [])
    .map((item) => {
      const value = toNumber(item.value)
      if (value === null) return null
      return {
        value,
        changePct: toNumber(item.changePct),
        rawValue: toNumber(item.rawValue),
        time: item.time || ''
      }
    })
    .filter(Boolean)

  if (!useTradingAxis(options) || points.length < 2) return points

  const sortable = points.map((point, index) => ({
    point,
    order: index,
    minuteIndex: tradingMinuteIndex(point.time, options)
  }))
  const validCount = sortable.filter((item) => item.minuteIndex !== null).length
  if (validCount < 2 || validCount < points.length * 0.8) return points

  const ordered = sortable
    .sort((left, right) => {
      if (left.minuteIndex !== null && right.minuteIndex !== null) {
        const delta = left.minuteIndex.index - right.minuteIndex.index
        return delta === 0 ? left.order - right.order : delta
      }
      if (left.minuteIndex !== null) return -1
      if (right.minuteIndex !== null) return 1
      return left.order - right.order
    })

  const deduped = []
  for (const item of ordered) {
    const previous = deduped.length ? deduped[deduped.length - 1] : null
    if (previous && previous.minuteIndex !== null && item.minuteIndex !== null && previous.minuteIndex.index === item.minuteIndex.index) {
      deduped[deduped.length - 1] = item
    } else {
      deduped.push(item)
    }
  }

  return deduped.map((item) => item.point)
}

function scaleFor(points, options = {}) {
  const values = points.map((item) => item.value)
  if (!values.length) return { min: -1, max: 1 }
  let min = Math.min.apply(null, values)
  let max = Math.max.apply(null, values)
  if (options.symmetric) {
    const bound = Math.max(Math.abs(min), Math.abs(max), 0.01)
    return { min: -bound, max: bound }
  }
  min = Math.min(min, 0)
  max = Math.max(max, 0)
  if (min === max) {
    const spread = Math.max(Math.abs(max), 1)
    min -= spread
    max += spread
  }
  const padding = (max - min) * 0.08
  return { min: min - padding, max: max + padding }
}

function coordinates(points, width, height, options = {}) {
  const area = chartArea(width, height)
  const scale = scaleFor(points, options)
  const range = scale.max - scale.min || 1
  const axisRange = timeAxisRange(points, options)
  const coordinateOptions = axisRange ? { ...options, timeAxisRange: axisRange } : options
  return points.map((item, index) => {
    const x = xForPoint(item, index, points.length, area, coordinateOptions)
    const y = area.top + (scale.max - item.value) / range * area.height
    return {
      x,
      y,
      minuteIndex: useTradingAxis(options) ? tradingMinuteIndex(item.time, options) : null
    }
  })
}

function timeAxisRange(points, options = {}) {
  if (!useTradingAxis(options) || !options.fitTimeAxisToData) return null
  const indexes = (points || [])
    .map((item) => tradingMinuteIndex(item.time, options))
    .filter((item) => item !== null)
    .map((item) => item.index)
  if (indexes.length < 2) return null
  const min = Math.min.apply(null, indexes)
  const max = Math.max.apply(null, indexes)
  if (max <= min) return null
  return { min, max }
}

function yForValue(value, width, height, points, options = {}) {
  const area = chartArea(width, height)
  const scale = scaleFor(points, options)
  const range = scale.max - scale.min || 1
  return area.top + (scale.max - value) / range * area.height
}

function pickColor(points) {
  if (!points.length) return '#8B949E'
  const last = points[points.length - 1]
  const value = last.changePct !== null ? last.changePct : last.value
  if (value > 0) return '#E14B5A'
  if (value < 0) return '#18A875'
  return '#8B949E'
}

function rgba(color, alpha) {
  if (color === '#E14B5A') return 'rgba(225,75,90,' + alpha + ')'
  if (color === '#18A875') return 'rgba(24,168,117,' + alpha + ')'
  return 'rgba(139,148,158,' + alpha + ')'
}

function fillStyle(width, height, color) {
  const area = chartArea(width, height)
  const top = area.top + 4
  const bottom = height - area.bottom
  return 'left:' + round(area.left) + 'px;right:' + round(area.right) + 'px;top:' + round(top) + 'px;height:' + round(Math.max(1, bottom - top)) + 'px;background:linear-gradient(180deg,' + rgba(color, 0.20) + ' 0%,' + rgba(color, 0.10) + ' 48%,' + rgba(color, 0) + ' 100%);'
}

function shouldBreakSegment(prev, next, totalDots, options = {}) {
  const dx = next.x - prev.x
  return dx < -0.5
}

export function buildLineChartModel(rawPoints, options = {}) {
  const width = Math.max(1, Number(options.width || 320))
  const height = Math.max(1, Number(options.height || 248))
  const points = normalizeLinePoints(rawPoints, options)
  const dots = points.length ? coordinates(points, width, height, options) : []
  const area = chartArea(width, height)
  const color = pickColor(points)
  const zeroY = clamp(yForValue(0, width, height, points, options), area.top, height - area.bottom)
  const activeIndex = Number.isInteger(options.activeIndex) ? options.activeIndex : -1

  const segments = []
  for (let index = 1; index < dots.length; index += 1) {
    const prev = dots[index - 1]
    const next = dots[index]
    if (shouldBreakSegment(prev, next, dots.length, options)) continue
    const dx = next.x - prev.x
    const dy = next.y - prev.y
    // Slightly overlap adjacent DOM segments so sub-pixel rounding cannot leave visible gaps.
    const length = Math.sqrt(dx * dx + dy * dy) + 1.5
    const angle = Math.atan2(dy, dx) * 180 / Math.PI
    segments.push({
      key: 's' + index,
      style: 'left:' + round(prev.x) + 'px;top:' + round(prev.y) + 'px;width:' + round(length) + 'px;transform:rotate(' + round(angle) + 'deg);background-color:' + color + ';'
    })
  }

  const active = activeIndex >= 0 && activeIndex < dots.length ? dots[activeIndex] : null
  const bubbleLeft = active ? clamp(active.x - 22, area.left, width - area.right - 44) : 0

  return {
    width,
    height,
    color,
    fillStyle: fillStyle(width, height, color),
    segments,
    dots: [],
    zeroStyle: 'top:' + round(zeroY) + 'px;',
    middleStyle: 'left:' + round(area.left + area.width / 2) + 'px;',
    activeLineStyle: active ? 'left:' + round(active.x) + 'px;' : '',
    activeDotStyle: active ? 'left:' + round(active.x - 4) + 'px;top:' + round(active.y - 4) + 'px;border-color:' + color + ';' : '',
    activeLabelStyle: active ? 'left:' + round(bubbleLeft) + 'px;top:' + round(height - 22) + 'px;' : ''
  }
}

function dateKey(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 10)
}

export function buildLineMarkerViews(rawPoints, rawMarkers, options = {}) {
  const width = Math.max(1, Number(options.width || 320))
  const height = Math.max(1, Number(options.height || 248))
  const points = normalizeLinePoints(rawPoints, options)
  if (!points.length || !rawMarkers || !rawMarkers.length) return []

  const dots = coordinates(points, width, height, options)
  const pointIndexByDate = {}
  points.forEach((point, index) => {
    const key = dateKey(point.time)
    if (key) pointIndexByDate[key] = index
  })

  const seen = {}
  const views = []
  rawMarkers.forEach((marker, markerIndex) => {
    const type = String(marker.type || marker.txnType || '').toUpperCase()
    const date = dateKey(marker.time || marker.confirmDate || marker.txnDate)
    const pointIndex = pointIndexByDate[date]
    const uniqueKey = date + '-' + type
    if ((type !== 'BUY' && type !== 'SELL') || pointIndex === undefined || seen[uniqueKey]) return
    const dot = dots[pointIndex]
    if (!dot) return
    seen[uniqueKey] = true
    views.push({
      key: 'trade-' + uniqueKey + '-' + markerIndex,
      type,
      date,
      nav: toNumber(marker.nav),
      style: 'left:' + round(dot.x - 5) + 'px;top:' + round(dot.y - 5) + 'px;'
    })
  })
  return views
}

export function locateLinePoint(rawPoints, touchX, width, options = {}) {
  const points = normalizeLinePoints(rawPoints, options)
  if (!points.length) return null
  const chartWidth = Math.max(1, Number(width || options.width || 320))
  const chartHeight = Math.max(1, Number(options.height || 248))
  const area = chartArea(chartWidth, chartHeight)
  const x = clamp(Number(touchX || 0), area.left, chartWidth - area.right)
  let index = 0
  if (options.today || options.symmetric) {
    let axisTotal = 240
    const axisRange = timeAxisRange(points, options)
    for (let i = 0; i < points.length; i += 1) {
      const minuteIndex = tradingMinuteIndex(points[i].time, options)
      if (minuteIndex !== null) {
        axisTotal = minuteIndex.total
        break
      }
    }
    const rangeMin = axisRange ? axisRange.min : 0
    const rangeWidth = axisRange ? axisRange.max - axisRange.min : axisTotal
    const targetMinute = Math.round(rangeMin + (x - area.left) / area.width * rangeWidth)
    let bestDistance = Number.MAX_VALUE
    for (let i = 0; i < points.length; i += 1) {
      const minuteIndex = tradingMinuteIndex(points[i].time, options)
      const distance = minuteIndex === null ? Math.abs(i - targetMinute) : Math.abs(minuteIndex.index - targetMinute)
      if (distance < bestDistance) {
        bestDistance = distance
        index = i
      }
    }
  } else {
    const ratio = points.length === 1 ? 0 : (x - area.left) / area.width
    index = clamp(Math.round(ratio * (points.length - 1)), 0, points.length - 1)
  }
  const dots = coordinates(points, chartWidth, chartHeight, options)
  return {
    ...points[index],
    index,
    x: dots[index].x,
    y: dots[index].y
  }
}

export function drawLineChart() {
  return Promise.resolve(false)
}
