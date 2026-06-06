const THEME_KEY = 'jk_theme_mode'
const TAB_BAR_PAGES = [
  'pages/index/index',
  'pages/watchlist/watchlist',
  'pages/holdings/holdings',
  'pages/information/information',
  'pages/me/me'
]

export function getThemeMode() {
  const value = uni.getStorageSync(THEME_KEY)
  return value === 'dark' ? 'dark' : 'light'
}

export function setThemeMode(mode) {
  const normalized = mode === 'dark' ? 'dark' : 'light'
  uni.setStorageSync(THEME_KEY, normalized)
  applyTheme()
  return normalized
}

export function applyTheme() {
  const dark = getThemeMode() === 'dark'
  if (uni.setTabBarStyle && isTabBarPage()) {
    const task = uni.setTabBarStyle({
      color: dark ? '#A7B0BE' : '#64706B',
      selectedColor: dark ? '#4EA3FF' : '#1677F2',
      backgroundColor: dark ? '#111827' : '#FFFFFF',
      borderStyle: dark ? 'white' : 'black',
      fail() {}
    })
    if (task && task.catch) task.catch(() => {})
  }
  if (uni.setNavigationBarColor) {
    const task = uni.setNavigationBarColor({
      frontColor: dark ? '#ffffff' : '#000000',
      backgroundColor: dark ? '#111827' : '#F7FAF8',
      fail() {}
    })
    if (task && task.catch) task.catch(() => {})
  }
}

function isTabBarPage() {
  try {
    if (typeof getCurrentPages !== 'function') return false
    const pages = getCurrentPages()
    const current = pages && pages.length ? pages[pages.length - 1] : null
    return !!current && TAB_BAR_PAGES.indexOf(current.route) >= 0
  } catch (e) {
    return false
  }
}
