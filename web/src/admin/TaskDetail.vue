<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { download, get, getAll, post, printRecord } from '../services/api'
import { date, duration, percent } from '../services/format'
import { useFeedback } from '../composables/useFeedback'
import type { Assignment, JsonRecord } from '../types'
import NoticeBox from '../components/NoticeBox.vue'
import StatusBadge from '../components/StatusBadge.vue'
import NotificationsList from './NotificationsList.vue'

const props = defineProps<{ taskId: string }>()
defineEmits<{ back: [] }>()
const task = ref<JsonRecord>({})
const assignments = ref<JsonRecord[]>([])
const selected = ref<Assignment | null>(null)
const tab = ref('progress')
const { busy, error, success, run } = useFeedback()
const completed = computed(() => assignments.value.filter(assignment => assignment.status === 'COMPLETED').length)
async function refresh() {
  const [training, people] = await Promise.all([get(`/admin/tasks/${props.taskId}`), getAll(`/admin/tasks/${props.taskId}/assignments`)])
  task.value = training
  assignments.value = people
  if (selected.value) selected.value = await get<Assignment>(`/admin/assignments/${selected.value.id}`)
}
onMounted(() => run(refresh))
async function detail(id: string) { await run(async () => { selected.value = await get<Assignment>(`/admin/assignments/${id}`) }) }
async function cancel() {
  const reason = prompt('请输入取消原因。取消后员工将无法继续本次学习，已有学习记录仍会保留。')
  if (!reason?.trim()) return
  await run(async () => { await post(`/admin/tasks/${props.taskId}/cancel`, { reason, expectedRevision: task.value.rowVersion }); await refresh() }, '本次培训已取消，已有学习记录保留。')
}
</script>
<template>
  <button class="link back-link" @click="$emit('back')">← 返回培训安排</button>
  <NoticeBox :text="error" kind="error" /><NoticeBox :text="success" kind="success" />
  <section class="panel">
    <div class="section-heading">
      <div><span v-if="task.status === 'ACTIVE'" class="badge active">已发布</span><StatusBadge v-else :value="task.status" /><h2>{{ task.title }}</h2><p class="muted">{{ task.description || '根据本次培训安排完成全部学习内容。' }}</p></div>
      <div class="actions"><button class="secondary" :disabled="busy" @click="run(refresh)" data-testid="task-refresh">更新进度</button><button class="secondary" @click="run(() => download(`/admin/tasks/${taskId}/records.csv`, `${task.title || '培训任务'}-记录.csv`))" data-testid="task-export-csv">导出培训记录</button><button v-if="task.status === 'ACTIVE'" class="text-danger" @click="cancel" data-testid="task-cancel">取消培训</button></div>
    </div>
    <div class="metadata"><span>完成截止：{{ task.dueAt ? date(task.dueAt) : '未设截止时间' }}</span><span>参训员工 {{ assignments.length }} 人</span><span>已完成 {{ completed }} 人</span><span>待完成 {{ assignments.length - completed }} 人</span></div>
  </section>
  <div class="tab-bar"><button :class="{ active: tab === 'progress' }" @click="tab = 'progress'" data-testid="task-tab-progress">员工学习进度</button><button :class="{ active: tab === 'notifications' }" @click="tab = 'notifications'" data-testid="task-tab-notifications">培训通知与提醒</button></div>
  <NotificationsList v-if="tab === 'notifications'" :task-id="taskId" :assignments="assignments" />
  <section v-else class="panel">
    <div class="section-heading"><div><h2>参训情况</h2><p class="muted small">查看每位员工的学习状态，及时跟进尚未完成的培训。</p></div><span class="counter">{{ assignments.length }} 位员工</span></div>
    <div class="table-wrap"><table><thead><tr><th>参训员工</th><th>学习状态</th><th>本人确认时间</th><th>培训记录</th></tr></thead><tbody>
      <tr v-for="assignment in assignments" :key="assignment.id" :data-testid="`assignment-row-${assignment.personName || assignment.name}`"><td><strong>{{ assignment.personName || assignment.name || assignment.personSnapshot?.name }}</strong><span class="cell-sub">{{ assignment.personNo || assignment.personSnapshot?.personNo }}</span></td><td><StatusBadge :value="assignment.status" /></td><td>{{ date(assignment.confirmedAt) }}</td><td><div class="actions"><button class="link" @click="detail(assignment.id)" data-testid="assignment-progress-detail">学习详情</button><button class="link" @click="run(() => printRecord(`/admin/assignments/${assignment.id}/print`))" data-testid="assignment-print">打印记录</button></div></td></tr>
    </tbody></table><div v-if="!assignments.length" class="empty">{{ busy ? '正在加载参训情况…' : '本次培训暂无参训员工。' }}</div></div>
  </section>
  <section v-if="selected" class="panel" data-testid="assignment-detail">
    <div class="section-heading"><div><p class="eyebrow">LEARNING DETAIL</p><h2>{{ selected.personName }}的学习详情</h2><p class="muted small">按本次发布的材料与要求查看学习进度。</p></div><button class="link" @click="selected = null">收起详情</button></div>
    <div v-for="material in selected.materials" :key="material.taskMaterialId || material.id" class="progress-item">
      <div class="section-heading"><h3>{{ material.title }}</h3><StatusBadge :value="material.progress?.requirementsMet ? 'SATISFIED' : 'IN_PROGRESS'" /></div>
      <div class="progress-track"><span :style="{ width: percent(material.progress?.coverageRatio || 0) }"></span></div>
      <div class="metadata"><span>{{ material.type === 'MP4' ? '培训视频' : '学习文档' }}</span><span>内容覆盖 {{ percent(material.progress?.coverageRatio) }}</span><span>有效学习 {{ duration(material.progress?.effectiveMs) }}</span></div>
    </div>
    <div class="metadata"><span>本人确认：{{ date(selected.confirmedAt) }}</span><span>学习完成：{{ date(selected.completedAt) }}</span></div>
    <p class="muted small">完成状态表示达到本次学习要求并由本人确认；若培训还要求岗位实操或考试，需按相应安排另行完成。</p>
  </section>
</template>
