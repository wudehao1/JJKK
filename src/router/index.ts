import { createRouter, createWebHistory } from 'vue-router'
import { getAuth } from '@/api/request'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/Home.vue'),
      meta: { tab: true }
    },
    {
      path: '/watchlist',
      name: 'watchlist',
      component: () => import('@/views/Watchlist.vue'),
      meta: { tab: true, auth: true }
    },
    {
      path: '/holdings',
      name: 'holdings',
      component: () => import('@/views/Holdings.vue'),
      meta: { tab: true, auth: true }
    },
    {
      path: '/information',
      name: 'information',
      component: () => import('@/views/Information.vue'),
      meta: { tab: true }
    },
    {
      path: '/me',
      name: 'me',
      component: () => import('@/views/Me.vue'),
      meta: { tab: true }
    },
    {
      path: '/fund/:fundCode',
      name: 'fund-detail',
      component: () => import('@/views/FundDetail.vue'),
      props: true
    },
    {
      path: '/information/:id',
      name: 'information-detail',
      component: () => import('@/views/InformationDetail.vue'),
      props: true
    },
    {
      path: '/market/:symbol',
      name: 'market-detail',
      component: () => import('@/views/MarketDetail.vue'),
      props: true
    },
    {
      path: '/sector-rankings',
      name: 'sector-rankings',
      component: () => import('@/views/SectorRankings.vue')
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('@/views/Search.vue')
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue')
    },
    {
      path: '/profit-analysis',
      name: 'profit-analysis',
      component: () => import('@/views/ProfitAnalysis.vue'),
      meta: { auth: true }
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.auth && !getAuth()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
