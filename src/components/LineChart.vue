<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue'

const props = defineProps<{
  points: { date: string; value: number }[]
  height?: number
  color?: string
  showGrid?: boolean
}>()

const canvasRef = ref<HTMLCanvasElement>()
const containerRef = ref<HTMLDivElement>()
const width = ref(320)

const effectiveHeight = computed(() => props.height || 200)

onMounted(() => {
  if (containerRef.value) {
    width.value = containerRef.value.clientWidth
  }
  draw()
})

watch(() => props.points, draw, { deep: true })

function draw() {
  const canvas = canvasRef.value
  if (!canvas || !props.points.length) return

  const dpr = window.devicePixelRatio || 1
  const w = width.value
  const h = effectiveHeight.value

  canvas.width = w * dpr
  canvas.height = h * dpr
  canvas.style.width = w + 'px'
  canvas.style.height = h + 'px'

  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.scale(dpr, dpr)

  const values = props.points.map(p => p.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const pad = 8

  // Draw grid
  if (props.showGrid !== false) {
    ctx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--color-border').trim() || '#EDF2F7'
    ctx.lineWidth = 0.5
    for (let i = 0; i <= 4; i++) {
      const y = pad + ((h - pad * 2) / 4) * i
      ctx.beginPath()
      ctx.moveTo(0, y)
      ctx.lineTo(w, y)
      ctx.stroke()
    }
  }

  // Draw line
  const color = props.color || getComputedStyle(document.documentElement).getPropertyValue('--color-primary').trim() || '#1677F2'
  ctx.strokeStyle = color
  ctx.lineWidth = 1.5
  ctx.lineJoin = 'round'
  ctx.beginPath()

  const points = props.points
  for (let i = 0; i < points.length; i++) {
    const x = pad + ((w - pad * 2) / (points.length - 1)) * i
    const y = h - pad - ((points[i].value - min) / range) * (h - pad * 2)
    if (i === 0) ctx.moveTo(x, y)
    else ctx.lineTo(x, y)
  }
  ctx.stroke()

  // Draw gradient fill
  const gradient = ctx.createLinearGradient(0, 0, 0, h)
  gradient.addColorStop(0, color + '30')
  gradient.addColorStop(1, color + '05')
  ctx.lineTo(w - pad, h - pad)
  ctx.lineTo(pad, h - pad)
  ctx.closePath()
  ctx.fillStyle = gradient
  ctx.fill()
}
</script>

<template>
  <div ref="containerRef" class="chart-container">
    <canvas ref="canvasRef"></canvas>
  </div>
</template>

<style scoped>
.chart-container { width: 100%; }
canvas { display: block; }
</style>
