<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAll } from '../services/api'
import { trainingThemes } from '../services/trainingThemes'
import { useFeedback } from '../composables/useFeedback'
import type { JsonRecord, Material, Person, TrainingTask } from '../types'
import AppIcon from '../components/AppIcon.vue'
import IndustryScene from '../components/IndustryScene.vue'
import NoticeBox from '../components/NoticeBox.vue'
const emit = defineEmits<{ navigate: [section: string]; create: [preset?: { title: string; description: string }]; openTask: [id: string] }>()
const tasks = ref<TrainingTask[]>([]), people = ref<Person[]>([]), materials = ref<Material[]>([]), bindings = ref<JsonRecord[]>([]), loaded = ref(false)
const { busy, error, run } = useFeedback()
async function refresh() {
  const result = await Promise.all([getAll<TrainingTask>('/admin/tasks'), getAll<Person>('/admin/people'), getAll<Material>('/admin/materials'), getAll<JsonRecord>('/admin/bindings')])
  ;[tasks.value, people.value, materials.value, bindings.value] = result
  loaded.value = true
}
onMounted(() => run(refresh))
const stats = computed(() => [
  { label: '培训任务', value: tasks.value.length, unit: '项', icon: 'book', tone: 'blue', note: '全部培训安排', target: 'tasks' },
  { label: '已发布培训', value: tasks.value.filter(t => t.status === 'ACTIVE').length, unit: '项', icon: 'calendar', tone: 'cyan', note: '当前有效的培训', target: 'tasks' },
  { label: '在册员工', value: people.value.filter(p => p.status === 'ACTIVE').length, unit: '人', icon: 'users', tone: 'violet', note: '已启用员工', target: 'people' },
  { label: '可用培训资源', value: materials.value.filter(m => m.status === 'READY' && m.compatibilityChecked === true).length, unit: '份', icon: 'folder', tone: 'amber', note: '可用于安排培训', target: 'materials' },
])
const recent = computed(() => [...tasks.value].sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || ''))).slice(0, 5))
const todos = computed(() => [
  { title: '员工加入申请', subtitle: '审核后即可参与培训', count: bindings.value.filter(b => b.status === 'PENDING').length, icon: 'users', target: 'people' },
  { title: '待发布培训', subtitle: '继续完善培训安排', count: tasks.value.filter(t => t.status === 'DRAFT').length, icon: 'file', target: 'tasks' },
  { title: '待检查培训资源', subtitle: '预览内容后投入使用', count: materials.value.filter(m => m.status === 'READY' && !m.compatibilityChecked).length, icon: 'folder', target: 'materials' },
])
function shortDate(value?: string) { return value ? new Date(value).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit' }) : '未设置' }
function state(status: string) { return status === 'ACTIVE' ? '已发布' : status === 'DRAFT' ? '待发布' : '已取消' }
</script>
<template>
  <div class="overview-page" data-testid="training-overview">
    <div class="page-heading overview-heading"><div><h1>培训工作台</h1><p>关注培训安排，支持每一位员工的专业成长。</p></div><div class="actions"><button class="icon-button" aria-label="刷新工作台" :disabled="busy" @click="run(refresh)"><AppIcon name="refresh" :size="18" /></button><button class="primary" @click="emit('create')" data-testid="overview-create"><AppIcon name="plus" :size="17" />创建培训</button></div></div>
    <NoticeBox :text="error" kind="error" />
    <section class="overview-hero"><div class="hero-copy"><span class="hero-kicker"><span></span>YONGSEN TRAINING</span><h2>夯实安全基础<br>提升专业能力</h2><p>让安全意识融入日常，让岗位能力持续成长。</p><button class="hero-action" @click="emit('create', trainingThemes[0]!.preset)">安排安全培训<AppIcon name="arrow" :size="17" /></button></div><IndustryScene /><span class="hero-caption">SAFETY · SKILLS · GROWTH</span></section>
    <div class="overview-stats"><button v-for="stat in stats" :key="stat.label" class="overview-stat" @click="emit('navigate', stat.target)"><div class="stat-top"><span>{{ stat.label }}</span><span class="stat-icon" :class="stat.tone"><AppIcon :name="stat.icon" :size="19" /></span></div><div class="stat-value"><strong>{{ loaded ? stat.value : '—' }}</strong><span>{{ stat.unit }}</span></div><div class="stat-caption">{{ stat.note }}<AppIcon name="chevron" :size="12" /></div></button></div>
    <section class="training-directions"><div class="section-heading"><div><h2>围绕业务，开展培训</h2><p class="section-description">从作业安全到个人成长，选择本次培训的方向。</p></div></div><div class="theme-grid"><button v-for="theme in trainingThemes" :key="theme.id" class="theme-card" :class="theme.color" @click="emit('create', theme.preset)" :data-testid="`training-theme-${theme.id}`"><div class="theme-card-top"><span class="theme-icon"><AppIcon :name="theme.icon" :size="24" /></span><span class="theme-label">{{ theme.label }}</span></div><h3>{{ theme.title }}</h3><p>{{ theme.description }}</p><span class="theme-action">创建培训<AppIcon name="arrow" :size="16" /></span></button></div></section>
    <div class="overview-bottom">
      <section class="panel recent-training"><div class="section-heading"><h2>最近培训</h2><button class="link" @click="emit('navigate', 'tasks')">查看全部<AppIcon name="chevron" :size="13" /></button></div><div v-if="!loaded" class="empty compact">{{ busy ? '正在加载培训安排…' : '暂未获取培训安排' }}</div><div v-else-if="!recent.length" class="empty"><span class="empty-icon"><AppIcon name="book" :size="28" /></span><h3>开启第一项培训</h3><p>选择培训资源与员工，安排一场有目标的学习。</p><button class="secondary" @click="emit('create')">创建培训</button></div><div v-else class="recent-task-list"><button v-for="task in recent" :key="task.id" class="recent-task" @click="emit('openTask', task.id)" :data-testid="`overview-task-${task.id}`"><span class="task-row-icon"><AppIcon name="book" :size="20" /></span><span class="recent-task-body"><strong>{{ task.title }}</strong><small>截止日期 {{ shortDate(task.dueAt) }}</small></span><span class="badge" :class="task.status.toLowerCase()">{{ state(task.status) }}</span><AppIcon name="chevron" :size="15" /></button></div></section>
      <section class="panel work-reminders"><div class="section-heading"><h2>工作待办</h2><span class="subtle-label">及时跟进</span></div><button v-for="todo in todos" :key="todo.title" class="todo-row" @click="emit('navigate', todo.target)"><span class="todo-icon"><AppIcon :name="todo.icon" :size="18" /></span><span><strong>{{ todo.title }}</strong><small>{{ todo.subtitle }}</small></span><b :class="{ 'has-todo': todo.count > 0 }">{{ loaded ? todo.count : '—' }}</b><AppIcon name="chevron" :size="13" /></button><div class="resource-tip"><span class="resource-tip-icon"><AppIcon name="folder" :size="21" /></span><div><strong>充实企业培训资源</strong><p>上传作业视频和学习资料，为培训做好准备。</p><button class="link" @click="emit('navigate', 'materials')">管理资源<AppIcon name="arrow" :size="14" /></button></div></div></section>
    </div>
  </div>
</template>
