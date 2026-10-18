<script setup lang="ts">
import { ref } from 'vue'
import { localPreview } from '../services/environment'
import PeoplePanel from './PeoplePanel.vue'
import MaterialsPanel from './MaterialsPanel.vue'
import TasksPanel from './TasksPanel.vue'
import SettingsPanel from './SettingsPanel.vue'
import LocalOutbox from './LocalOutbox.vue'
import TrainingOverview from './TrainingOverview.vue'
import AppIcon from '../components/AppIcon.vue'
import AdminOnboarding from './AdminOnboarding.vue'
defineEmits<{ logout: [] }>()
const active = ref('overview'), taskPanelKey = ref(0)
const onboarding = ref<InstanceType<typeof AdminOnboarding>>()
const tutorialOpen = ref(false)
const initialTraining = ref<{ title: string; description: string }>(), initialTaskId = ref<string>()
const nav = [
  { id: 'overview', title: '培训工作台', icon: 'grid' },
  { id: 'tasks', title: '培训任务', icon: 'book' },
  { id: 'materials', title: '培训资源', icon: 'folder' },
  { id: 'people', title: '员工管理', icon: 'users' },
]
const today = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
function navigate(id: string) { initialTraining.value = undefined; initialTaskId.value = undefined; taskPanelKey.value++; active.value = id }
function createTraining(preset = { title: '', description: '' }) { initialTraining.value = preset; initialTaskId.value = undefined; taskPanelKey.value++; active.value = 'tasks' }
function openTask(id: string) { initialTaskId.value = id; initialTraining.value = undefined; taskPanelKey.value++; active.value = 'tasks' }
</script>
<template>
  <div class="app-shell" :inert="tutorialOpen || undefined">
    <aside class="sidebar">
      <div class="sidebar-brand"><div class="brand-symbol"><span></span><span></span></div><div><div class="brand-mark">永森油服</div><div class="sidebar-subtitle">企业培训平台</div></div></div>
      <div class="nav-section-label">培训中心</div>
      <nav aria-label="主导航"><button v-for="item in nav" :key="item.id" :class="{ active: active === item.id }" @click="navigate(item.id)" :aria-current="active === item.id ? 'page' : undefined" :data-testid="`nav-${item.id}`" :data-tour="item.id"><AppIcon :name="item.icon" /><span>{{ item.title }}</span><span v-if="active === item.id" class="nav-active-dot"></span></button></nav>
      <div class="sidebar-bottom"><div class="nav-section-label">平台管理</div><nav aria-label="平台管理"><button :class="{ active: active === 'settings' }" @click="navigate('settings')" data-testid="nav-settings" data-tour="settings"><AppIcon name="settings" />通知设置</button><button v-if="localPreview" :class="{ active: active === 'outbox' }" @click="navigate('outbox')" data-testid="nav-outbox"><AppIcon name="bell" />通知测试<span class="nav-local-dot"></span></button></nav>
        <div class="sidebar-footer"><div class="account-avatar">管</div><div><strong>管理员</strong><small>企业管理端</small></div><button class="logout-button" aria-label="退出登录" @click="$emit('logout')" data-testid="logout"><AppIcon name="logout" :size="18" /></button></div>
      </div>
    </aside>
    <main class="workspace">
      <header class="workspace-top"><div class="breadcrumb"><span>培训中心</span><AppIcon name="chevron" :size="12" /><strong>{{ nav.find(n => n.id === active)?.title || (active === 'settings' ? '通知设置' : '通知测试') }}</strong></div><div class="topbar-meta"><button type="button" class="tutorial-trigger" @click="onboarding?.replay()" data-testid="onboarding-replay"><AppIcon name="help" :size="16" /><span>使用教程</span></button><span v-if="localPreview" class="environment-label">本地预览</span><span class="topbar-date"><AppIcon name="calendar" :size="15" />{{ today }}</span></div></header>
      <div class="workspace-content">
        <TrainingOverview v-if="active === 'overview'" @navigate="navigate" @create="createTraining" @open-task="openTask" />
        <TasksPanel v-else-if="active === 'tasks'" :key="taskPanelKey" :initial-training="initialTraining" :initial-task-id="initialTaskId" />
        <PeoplePanel v-else-if="active === 'people'" /><MaterialsPanel v-else-if="active === 'materials'" /><SettingsPanel v-else-if="active === 'settings'" /><LocalOutbox v-else />
        <footer class="workspace-footer">永森油服 · 企业培训平台<span>安全作业 · 专业成长</span></footer>
      </div>
    </main>
  </div>
  <AdminOnboarding ref="onboarding" @open-change="tutorialOpen = $event" />
</template>

<style scoped>
.tutorial-trigger{display:flex;align-items:center;gap:6px;background:#f6f9fe;border:1px solid #e1eaf6;color:#5578a8;border-radius:6px;padding:6px 9px;font-size:11px;white-space:nowrap}
.tutorial-trigger:hover{background:#edf4ff;border-color:#c4d8f5;color:#2e66b3}
@media(max-width:760px){.tutorial-trigger{gap:4px;padding:5px 6px;font-size:10px}.workspace-top{gap:8px}.breadcrumb{gap:7px;min-width:0}.breadcrumb strong{white-space:nowrap}.topbar-meta{flex-shrink:0}}
@media(max-width:420px){.workspace-top .environment-label{display:none}}
</style>
