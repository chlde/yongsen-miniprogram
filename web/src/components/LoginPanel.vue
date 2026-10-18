<script setup lang="ts">
import { ref } from 'vue'
import { post } from '../services/api'
import { localPreview } from '../services/environment'
import { useFeedback } from '../composables/useFeedback'
import type { AuthSession, Role } from '../types'
import NoticeBox from './NoticeBox.vue'
import AppIcon from './AppIcon.vue'
import IndustryScene from './IndustryScene.vue'
const props = defineProps<{ initialRole?: Role }>()
const emit = defineEmits<{ login: [session: AuthSession] }>()
const role = ref<Role>(localPreview ? props.initialRole || 'ADMIN' : 'ADMIN')
const username = ref('admin'), password = ref(''), identity = ref('alice')
const { busy, error, run } = useFeedback()
async function login() {
  await run(async () => {
    if (role.value === 'LEARNER' && !localPreview) throw new Error('浏览器学员预览仅在本地网页与本地 API 之间启用。正式学员请使用微信小程序。')
    const data = role.value === 'ADMIN'
      ? await post('/admin/auth/login', { username: username.value, password: password.value })
      : await post('/auth/wechat-login', { code: `mock:${identity.value.trim()}` })
    if (!data.accessToken) throw new Error('登录响应缺少业务会话，请检查服务配置。')
    emit('login', { token: data.accessToken, role: role.value, identity: role.value === 'LEARNER' ? identity.value : undefined })
  })
}
</script>
<template>
  <main class="login-layout">
    <section class="login-story">
      <div class="login-brand"><div class="brand-symbol"><span></span><span></span></div><div><div class="brand-mark">永森油服</div><span>企业培训平台</span></div></div>
      <div class="login-story-content"><p class="eyebrow">YONGSEN · CORPORATE LEARNING</p><h1>安全作业，<br>从专业培训开始。</h1><p class="login-description">面向一线作业与员工成长，<br>让每一次学习成为前行的力量。</p><div class="login-themes"><span><AppIcon name="shield" :size="17" />安全生产培训</span><span><AppIcon name="tools" :size="17" />岗位技能培训</span><span><AppIcon name="growth" :size="17" />员工能力提升</span></div></div>
      <IndustryScene /><div class="login-story-footer"><span>专注行业 · 培育人才</span><span>YONGSEN OILFIELD SERVICES</span></div>
    </section>
    <section class="login-form-side">
      <div class="login-card">
        <div class="login-welcome"><span class="login-welcome-line"></span><span>欢迎回来</span><span v-if="localPreview" class="environment-label">本地预览</span></div><h2>登录企业培训平台</h2><p class="login-form-intro">组织高效培训，助力团队成长。</p>
        <div v-if="localPreview" class="segmented"><button :class="{ active: role === 'ADMIN' }" @click="role = 'ADMIN'" data-testid="role-admin">管理端</button><button :class="{ active: role === 'LEARNER' }" @click="role = 'LEARNER'" data-testid="role-learner">学员预览</button></div>
        <form @submit.prevent="login" class="form-stack">
          <template v-if="role === 'ADMIN'"><label>管理员账号<input v-model="username" placeholder="请输入管理员账号" autocomplete="username" required data-testid="admin-username"></label><label>登录密码<input v-model="password" placeholder="请输入登录密码" type="password" autocomplete="current-password" required data-testid="admin-password"></label></template>
          <template v-else><div class="local-callout"><strong>本地学员预览</strong><p>使用模拟身份体验学习流程。正式学习请使用微信小程序。</p></div><label>测试身份<input v-model="identity" autocomplete="off" required pattern="[a-zA-Z0-9_-]+" data-testid="learner-identity"><small>同一名称对应同一测试学员，例如 alice。</small></label></template>
          <NoticeBox :text="error" kind="error" />
          <button class="primary large login-submit" :disabled="busy" data-testid="login-submit"><span>{{ busy ? '正在登录…' : role === 'ADMIN' ? '登录管理端' : '进入学员预览' }}</span><AppIcon name="arrow" :size="18" /></button>
        </form>
        <div class="login-help"><AppIcon name="shield" :size="17" /><span>企业内部培训平台，账号问题请联系管理员。</span></div>
      </div>
      <footer class="login-footer"><span>永森油服 · 企业培训平台</span><span>安全意识 / 专业能力 / 团队成长</span></footer>
    </section>
  </main>
</template>
