<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAll } from '../services/api'
import { date } from '../services/format'
import { useFeedback } from '../composables/useFeedback'
import type { TrainingTask } from '../types'
import TaskComposer from './TaskComposer.vue'
import TaskDetail from './TaskDetail.vue'
import StatusBadge from '../components/StatusBadge.vue'
import NoticeBox from '../components/NoticeBox.vue'

const props = defineProps<{ initialTraining?: { title: string; description: string }; initialTaskId?: string }>()
const tasks = ref<TrainingTask[]>([])
const creating = ref(false)
const selected = ref('')
const composerPreset = ref<{ title: string; description: string }>()
const query = ref('')
const filter = ref('ALL')
const { busy, error, run } = useFeedback()
const filteredTasks = computed(() => tasks.value.filter(task =>
  (filter.value === 'ALL' || task.status === filter.value) && `${task.title} ${task.description || ''}`.toLowerCase().includes(query.value.trim().toLowerCase())))
async function refresh() { tasks.value = await getAll<TrainingTask>('/admin/tasks') }
function createTraining(preset?: { title: string; description: string }) {
  composerPreset.value = preset
  creating.value = true
  selected.value = ''
}
onMounted(() => {
  if (props.initialTraining) createTraining(props.initialTraining)
  else if (props.initialTaskId) selected.value = props.initialTaskId
  void run(refresh)
})
async function created(id: string) { creating.value = false; selected.value = id; await run(refresh) }
</script>
<template>
  <div class="page-heading">
    <div><p class="eyebrow">TRAINING MANAGEMENT</p><h1>培训任务</h1><p>统一安排安全生产、岗位技能与员工能力培训，让每项学习有序开展。</p></div>
    <div class="actions"><button class="secondary" :disabled="busy" @click="run(refresh)">刷新列表</button><button class="primary" @click="createTraining()" data-testid="task-create">＋ 发布培训</button></div>
  </div>
  <NoticeBox :text="error" kind="error" />
  <TaskComposer v-if="creating" :initial-training="composerPreset" @created="created" @cancel="creating = false" />
  <TaskDetail v-else-if="selected" :key="selected" :task-id="selected" @back="selected = ''; run(refresh)" />
  <template v-else>
    <div class="stats-grid">
      <div class="stat"><span>培训任务总数</span><strong>{{ tasks.length }}</strong></div>
      <div class="stat"><span>已发布培训</span><strong>{{ tasks.filter(task => task.status === 'ACTIVE').length }}</strong></div>
      <div class="stat"><span>已取消培训</span><strong>{{ tasks.filter(task => task.status === 'CANCELLED').length }}</strong></div>
    </div>
    <section class="panel">
      <div class="section-heading">
        <div><h2>培训安排</h2><p class="muted small">查找培训内容，了解人员参与情况并跟进学习。</p></div>
        <div class="actions"><input v-model="query" class="search" placeholder="搜索培训名称或内容" aria-label="搜索培训" /><select v-model="filter" aria-label="培训状态"><option value="ALL">全部状态</option><option value="ACTIVE">已发布</option><option value="DRAFT">草稿</option><option value="CANCELLED">已取消</option></select></div>
      </div>
      <div v-if="!tasks.length" class="empty"><div class="empty-icon">▤</div><h3>{{ busy ? '正在加载培训安排…' : '发布第一项培训' }}</h3><p>从一次班组安全学习、一项岗位技能训练或员工成长课程开始。</p><button class="primary" @click="createTraining()">创建培训任务</button></div>
      <div v-else-if="!filteredTasks.length" class="empty compact">没有找到匹配的培训，请调整关键词或状态。</div>
      <div v-for="task in filteredTasks" :key="task.id" class="task-list-row" :data-testid="`task-row-${task.title}`">
        <div><span v-if="task.status === 'ACTIVE'" class="badge active">已发布</span><StatusBadge v-else :value="task.status" /><h3>{{ task.title }}</h3><p class="muted small">{{ task.description || '进入培训查看学习安排与人员进度。' }}</p><div class="metadata"><span>截止时间：{{ task.dueAt ? date(task.dueAt) : '未设截止时间' }}</span><span v-if="Array.isArray(task.personIds)">参训人员 {{ task.personIds.length }} 人</span></div></div>
        <button class="secondary" @click="selected = task.id" data-testid="task-detail">管理培训 →</button>
      </div>
    </section>
  </template>
</template>
