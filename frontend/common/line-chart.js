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

function tradingMinuteIndex(text, options = {}) {
  const minute = minuteOfDay(text)
  if (minute === null) return null
  const market = options.market || ''
  if (market === 'HKEX') {
    return continuousMinuteIndex(minute, 9 * 60 + 30, 16 * 60 + 10)
  }
  if (market === 'NYSE' || market === 'NASDAQ' || market === 'AMEX') {
    return continuousMinuteIndex(minute, 21 * 60 + 30, 4 * 60)
  }
  const morningStart = 9 * 60 + 30
  const morningEnd = 11 * 60 + 30
  const afternoonStart = 13 * 60
  const close = 15 * 60
  if (minute <= morningStart) return { index: 0, total: 240 }
  if (minute <= morningEnd) return { index: minute - morningStart, total: 240 }
  if (minute < afternoonStart) return { index: 120, total: 240 }
  if (minute <= close) return { index: 120 + minute - afternoonStart, total: 240 }
  return { index: 240, total: 240 }
}

function xForPoint(item, index, total, area, options = {}) {
  if (options.fixedTimeAxis !== false && (options.today || options.symmetric)) {
    const minuteIndex = tradingMinuteIndex(item.time, options)
    if (minuteIndex !== null) {
      return area.left + area.width * clamp(minuteIndex.index, 0, minuteIndex.total) / minuteIndex.total
    }
  }
  return total === 1 ? area.left + area.width / 2 : area.left + area.width * index / (total - 1)
}

export function normalizeLinePoints(rawPoints) {
  return (rawPoints || [])
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
  return points.map((item, index) => {
    const x = xForPoint(item, index, points.length, area, options)
    const y = area.top + (scale.max - item.value) / range * area.height
    return { x, y }
  })
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

export function buildLineChartModel(rawPoints, options = {}) {
  const width = Math.max(1, Number(options.width || 320))
  const height = Math.max(1, Number(options.height || 248))
  const points = normalizeLinePoints(rawPoints)
  const dots = points.length ? coordinates(points, width, height, options) : []
  const area = chartArea(width, height)
  const color = pickColor(points)
  const zeroY = clamp(yForValue(0, width, height, points, options), area.top, height - area.bottom)
  const activeIndex = Number.isInteger(options.activeIndex) ? options.activeIndex : -1

  const segments = []
  if (dots.length === 1) {
    const point = dots[0]
    const length = 54
    const left = clamp(point.x - length / 2, area.left, width - area.right - length)
    segments.push({
      key: 's0',
      style: 'left:' + round(left) + 'px;top:' + round(point.y) + 'px;width:' + length + 'px;transform:rotate(0deg);background-color:' + color + ';'
    })
  }
  for (let index = 1; index < dots.length; index += 1) {
    const prev = dots[index - 1]
    const next = dots[index]
    const dx = next.x - prev.x
    const dy = next.y - prev.y
    const length = Math.sqrt(dx * dx + dy * dy)
    const angle = Math.atan2(dy, dx) * 180 / Math.PI
    segments.push({
      key: 's' + index,
      style: 'left:' + round(prev.x) + 'px;top:' + round(prev.y) + 'px;width:' + round(length) + 'px;transform:rotate(' + round(angle) + 'deg);background-color:' + color + ';'
    })
  }

  const pointViews = dots.map((point, index) => ({
    key: 'p' + index,
    style: 'left:' + round(point.x - 1) + 'px;top:' + round(point.y - 1) + 'px;background-color:' + color + ';'
  }))

  const active = activeIndex >= 0 && activeIndex < dots.length ? dots[activeIndex] : null
  const bubbleLeft = active ? clamp(active.x - 22, area.left, width - area.right - 44) : 0

  return {
    width,
    height,
    color,
    fillStyle: fillStyle(width, height, color),
    segments,
    dots: pointViews,
    zeroStyle: 'top:' + round(zeroY) + 'px;',
    middleStyle: 'left:' + round(area.left + area.width / 2) + 'px;',
    activeLineStyle: active ? 'left:' + round(active.x) + 'px;' : '',
    activeDotStyle: active ? 'left:' + round(active.x - 4) + 'px;top:' + round(active.y - 4) + 'px;border-color:' + color + ';' : '',
    activeLabelStyle: active ? 'left:' + round(bubbleLeft) + 'px;top:' + round(height - 22) + 'px;' : ''
  }
}

export function locateLinePoint(rawPoints, touchX, width, options = {}) {
  const points = normalizeLinePoints(rawPoints)
  if (!points.length) return null
  const chartWidth = Math.max(1, Number(width || options.width || 320))
  const chartHeight = Math.max(1, Number(options.height || 248))
  const area = chartArea(chartWidth, chartHeight)
  const x = clamp(Number(touchX || 0), area.left, chartWidth - area.right)
  let index = 0
  if (options.today || options.symmetric) {
    let axisTotal = 240
    for (let i = 0; i < points.length; i += 1) {
      const minuteIndex = tradingMinuteIndex(points[i].time, options)
      if (minuteIndex !== null) {
        axisTotal = minuteIndex.total
        break
      }
    }
    const targetMinute = Math.round((x - area.left) / area.width * axisTotal)
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
