<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { useTheme } from '@/composables/useTheme'
import { useToast } from '@/composables/useToast'
import { getUserProfile, updateUserProfile, submitFeedback } from '@/api/user'

const auth = useAuthStore()
const router = useRouter()
const toast = useToast()
const { mode, setMode } = useTheme()

const profile = ref<any>(null)
const showEditProfile = ref(false)
const showFeedback = ref(false)
const editNickname = ref('')
const feedbackContent = ref('')
const saving = ref(false)

onMounted(async () => {
  if (auth.isLoggedIn) {
    try { profile.value = await getUserProfile(auth.userId) } catch { /* silent */ }
  }
})

function goLogin() { router.push('/login') }
function goProfit() { router.push('/profit-analysis') }
function doLogout() { auth.logout(); router.replace('/'); toast.info('已退出登录') }

function openEditProfile() {
  editNickname.value = profile.value?.nickname || auth.nickname || ''
  showEditProfile.value = true
}

async function saveProfile() {
  if (!editNickname.value.trim()) { toast.info('昵称不能为空'); return }
  saving.value = true
  try {
    await updateUserProfile(auth.userId, { nickname: editNickname.value.trim() })
    showEditProfile.value = false
    toast.success('资料已更新')
    profile.value = await getUserProfile(auth.userId)
  } catch { /* silent */ } finally {
    saving.value = false
  }
}

async function doSubmitFeedback() {
  if (!feedbackContent.value.trim()) { toast.info('请输入反馈内容'); return }
  saving.value = true
  try {
    await submitFeedback({ content: feedbackContent.value.trim() })
    showFeedback.value = false
    feedbackContent.value = ''
    toast.success('反馈已提交，感谢！')
  } catch { /* silent */ } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page me-page">
    <div class="page-header"><h1 class="page-title">我的</h1></div>

    <!-- User card -->
    <div class="user-card">
      <div class="avatar" v-if="auth.isLoggedIn">{{ (profile?.nickname || auth.nickname || '?')[0] }}</div>
      <div class="avatar guest" v-else>?</div>
      <div class="user-info">
        <div class="user-name">{{ auth.isLoggedIn ? (profile?.nickname || auth.nickname) : '未登录' }}</div>
        <div class="user-id" v-if="auth.isLoggedIn">ID: {{ auth.userId }}</div>
      </div>
      <button v-if="!auth.isLoggedIn" class="action-btn" @click="goLogin">登录</button>
      <button v-else class="action-btn outline" @click="openEditProfile">编辑</button>
    </div>

    <!-- Menu -->
    <div class="menu-section" v-if="auth.isLoggedIn">
      <div class="menu-item" @click="goProfit">
        <span class="menu-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 6l-9.5 9.5-5-5L1 18"/><path d="M17 6h6v6"/></svg>
        </span>
        <span class="menu-label">盈亏分析</span>
        <span class="menu-arrow">&rsaquo;</span>
      </div>
      <div class="menu-item" @click="showFeedback = true">
        <span class="menu-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>
        </span>
        <span class="menu-label">意见反馈</span>
        <span class="menu-arrow">&rsaquo;</span>
      </div>
    </div>

    <!-- Theme -->
    <div class="menu-section">
      <div class="menu-item">
        <span class="menu-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/></svg>
        </span>
        <span class="menu-label">深色模式</span>
        <div class="theme-switch">
          <button :class="{ active: mode === 'light' }" @click="setMode('light')">浅色</button>
          <button :class="{ active: mode === 'dark' }" @click="setMode('dark')">深色</button>
          <button :class="{ active: mode === 'system' }" @click="setMode('system')">跟随</button>
        </div>
      </div>
    </div>

    <!-- Logout -->
    <div class="menu-section" v-if="auth.isLoggedIn">
      <div class="menu-item" @click="doLogout">
        <span class="menu-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
        </span>
        <span class="menu-label logout-text">退出登录</span>
      </div>
    </div>

    <!-- Edit profile popup -->
    <Teleport to="body">
      <div v-if="showEditProfile" class="popup-mask" @click.self="showEditProfile = false">
        <div class="popup-panel">
          <div class="popup-head">
            <span class="popup-title">编辑资料</span>
            <button class="popup-close" @click="showEditProfile = false">&times;</button>
          </div>
          <div class="popup-body">
            <div class="form-row">
              <label>昵称</label>
              <input v-model="editNickname" class="form-input" type="text" maxlength="20" placeholder="输入昵称" />
            </div>
          </div>
          <button class="popup-confirm" :disabled="saving" @click="saveProfile">{{ saving ? '保存中...' : '保存' }}</button>
        </div>
      </div>
    </Teleport>

    <!-- Feedback popup -->
    <Teleport to="body">
      <div v-if="showFeedback" class="popup-mask" @click.self="showFeedback = false">
        <div class="popup-panel">
          <div class="popup-head">
            <span class="popup-title">意见反馈</span>
            <button class="popup-close" @click="showFeedback = false">&times;</button>
          </div>
          <div class="popup-body">
            <textarea v-model="feedbackContent" class="form-textarea" rows="5" placeholder="请输入你的建议或问题..." maxlength="500"></textarea>
          </div>
          <button class="popup-confirm" :disabled="saving" @click="doSubmitFeedback">{{ saving ? '提交中...' : '提交' }}</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.me-page { padding-bottom: 72px; }
.page-header { padding: 16px 16px 8px; }
.page-title { font-size: 20px; font-weight: 800; color: var(--color-text-primary); margin: 0; }
.user-card {
  display: flex; align-items: center; gap: 12px;
  margin: 8px 16px; padding: 16px; border-radius: 12px; background: var(--color-bg-card);
}
.avatar {
  width: 48px; height: 48px; border-radius: 24px;
  background: var(--color-primary); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 20px; font-weight: 800;
}
.avatar.guest { background: var(--color-bg-secondary); color: var(--color-text-tertiary); }
.user-info { flex: 1; }
.user-name { font-size: 16px; font-weight: 700; color: var(--color-text-primary); }
.user-id { font-size: 12px; color: var(--color-text-secondary); margin-top: 2px; }
.action-btn {
  border: none; border-radius: 8px; padding: 8px 16px;
  background: var(--color-primary); color: #fff;
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.action-btn.outline { background: var(--color-bg-secondary); color: var(--color-text-secondary); }

.menu-section { margin: 8px 16px; background: var(--color-bg-card); border-radius: 10px; overflow: hidden; }
.menu-item {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px; border-bottom: 1px solid var(--color-border);
  cursor: pointer;
}
.menu-item:last-child { border-bottom: none; }
.menu-icon { color: var(--color-text-secondary); display: flex; align-items: center; }
.menu-label { flex: 1; font-size: 14px; color: var(--color-text-primary); }
.menu-arrow { color: var(--color-text-tertiary); font-size: 18px; }
.logout-text { color: #DC2626; }
.theme-switch {
  display: flex; gap: 2px; background: var(--color-bg-secondary); border-radius: 6px; padding: 2px;
}
.theme-switch button {
  border: none; border-radius: 4px; padding: 4px 10px;
  background: transparent; color: var(--color-text-secondary);
  font-size: 12px; cursor: pointer;
}
.theme-switch button.active { background: var(--color-bg-card); color: var(--color-primary); font-weight: 600; }

.popup-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: flex-end; justify-content: center; z-index: 200;
}
.popup-panel {
  width: 100%; max-width: 480px; background: var(--color-bg-card);
  border-radius: 16px 16px 0 0; padding: 20px;
}
.popup-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.popup-title { font-size: 17px; font-weight: 700; color: var(--color-text-primary); }
.popup-close {
  width: 32px; height: 32px; border: none; border-radius: 16px;
  background: var(--color-bg-secondary); color: var(--color-text-secondary);
  font-size: 18px; cursor: pointer; display: flex; align-items: center; justify-content: center;
}
.popup-body { margin-bottom: 16px; }
.form-row { margin-bottom: 12px; }
.form-row label { display: block; font-size: 13px; color: var(--color-text-secondary); margin-bottom: 4px; }
.form-input {
  width: 100%; height: 40px; border: 1px solid var(--color-border); border-radius: 8px;
  padding: 0 12px; font-size: 14px; color: var(--color-text-primary);
  background: var(--color-bg); outline: none; box-sizing: border-box;
}
.form-input:focus { border-color: var(--color-primary); }
.form-textarea {
  width: 100%; border: 1px solid var(--color-border); border-radius: 8px;
  padding: 10px 12px; font-size: 14px; color: var(--color-text-primary);
  background: var(--color-bg); outline: none; box-sizing: border-box;
  resize: vertical; font-family: inherit;
}
.form-textarea:focus { border-color: var(--color-primary); }
.popup-confirm {
  width: 100%; height: 44px; border: none; border-radius: 10px;
  background: var(--color-primary); color: #fff;
  font-size: 15px; font-weight: 700; cursor: pointer;
}
.popup-confirm:disabled { opacity: 0.6; }
</style>
