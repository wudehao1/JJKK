import { ref, onMounted, onUnmounted } from 'vue'

const THEME_KEY = 'jk_theme_mode'
type ThemeMode = 'light' | 'dark' | 'system'

const currentTheme = ref<'light' | 'dark'>('light')
let mediaQuery: MediaQueryList | null = null
let listener: (() => void) | null = null

function applyTheme(mode: 'light' | 'dark') {
  document.documentElement.setAttribute('data-theme', mode)
  currentTheme.value = mode
}

function resolveSystem(): 'light' | 'dark' {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function useTheme() {
  const mode = ref<ThemeMode>((localStorage.getItem(THEME_KEY) as ThemeMode) || 'system')

  function setMode(m: ThemeMode) {
    mode.value = m
    localStorage.setItem(THEME_KEY, m)
    applyTheme(m === 'system' ? resolveSystem() : m)
  }

  onMounted(() => {
    applyTheme(mode.value === 'system' ? resolveSystem() : mode.value)
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    listener = () => {
      if (mode.value === 'system') applyTheme(resolveSystem())
    }
    mediaQuery.addEventListener('change', listener)
  })

  onUnmounted(() => {
    if (mediaQuery && listener) mediaQuery.removeEventListener('change', listener)
  })

  return { currentTheme, mode, setMode }
}
