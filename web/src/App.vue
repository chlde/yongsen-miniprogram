<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { AuthSession, Role } from './types'
import { clearAuth, loadAuth, post, saveAuth } from './services/api'
import { localPreview } from './services/environment'
import LoginPanel from './components/LoginPanel.vue'
import AdminDashboard from './admin/AdminDashboard.vue'
import LearnerDashboard from './learner/LearnerDashboard.vue'

const session = ref<AuthSession | null>(loadAuth())
if (session.value?.role === 'LEARNER' && !localPreview) { clearAuth(); session.value = null }
const hash = ref(location.hash)
const requestedRole = computed<Role>(() => localPreview && hash.value.startsWith('#learner') ? 'LEARNER' : 'ADMIN')
function onHash() { hash.value = location.hash }
onMounted(() => window.addEventListener('hashchange', onHash))
onUnmounted(() => window.removeEventListener('hashchange', onHash))
function login(value: AuthSession) { if (value.role === 'LEARNER' && !localPreview) { clearAuth(); return }; saveAuth(value); session.value = value; if (!location.hash) location.hash = value.role === 'LEARNER' ? 'learner' : 'admin' }
async function logout() { if (session.value?.role === 'ADMIN') await post('/admin/auth/logout').catch(() => {}); clearAuth(); session.value = null }
</script>
<template>
  <LoginPanel v-if="!session" :initial-role="requestedRole" @login="login" />
  <AdminDashboard v-else-if="session.role === 'ADMIN'" @logout="logout" />
  <LearnerDashboard v-else :identity="session.identity" :hash="hash" @logout="logout" />
</template>
