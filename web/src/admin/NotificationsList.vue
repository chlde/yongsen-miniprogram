<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAll, post } from '../services/api'
import { date } from '../services/format'
import { useFeedback } from '../composables/useFeedback'
import type { JsonRecord } from '../types'
import NoticeBox from '../components/NoticeBox.vue'
import StatusBadge from '../components/StatusBadge.vue'

const props = defineProps<{ taskId: string; assignments: JsonRecord[] }>()
const notifications = ref<JsonRecord[]>([])
const personIds = ref<string[]>([])
const reason = ref('请及时完成本次培训学习，感谢配合。')
const duplicate = ref(false)
const eligible = computed(() => props.assignments.filter(assignment => assignment.status !== 'COMPLETED'))
const { busy, error, success, run } = useFeedback()
async function refresh() { notifications.value = await getAll(`/admin/tasks/${props.taskId}/notifications`) }
onMounted(() => run(refresh))
async function remind() {
  if (!personIds.value.length) { error.value = '请选择需要提醒的员工。'; return }
  await run(async () => {
    const result = await post(`/admin/tasks/${props.taskId}/reminders`, { personIds: personIds.value, reason: reason.value, confirmPossibleDuplicate: duplicate.value })
    await refresh()
    success.value = `已处理本次提醒：${result.queuedCount ?? result.notifications?.length ?? 0} 条通知待发送，${result.skippedCount ?? 0} 条跳过。实际送达请查看通知状态。`
  })
}
function name(personId: string) { const assignment = props.assignments.find(item => item.personId === personId); return assignment?.personName || assignment?.name || '参训员工' }
</script>
<template>
  <section class="panel">
    <div class="section-heading"><div><h2>培训通知</h2><p class="muted small">了解员工是否收到提醒，以及何时通过通知进入本次培训。</p></div><button class="secondary" :disabled="busy" @click="run(refresh)" data-testid="notifications-refresh">更新通知状态</button></div>
    <NoticeBox :text="error" kind="error" /><NoticeBox :text="success" kind="success" />
    <div class="table-wrap"><table><thead><tr><th>参训员工</th><th>发送状态</th><th>送达情况</th><th>进入培训时间</th><th>通知渠道</th></tr></thead><tbody>
      <tr v-for="notification in notifications" :key="notification.id" data-testid="notification-row"><td><strong>{{ name(notification.personId) }}</strong><span class="cell-sub">{{ notification.errorMessage || notification.reason || notification.error || notification.sendErrcode || '' }}</span></td><td><span v-if="notification.sendStatus === 'ACCEPTED'" class="badge accepted">{{ notification.providerMode === 'mock' ? '模拟发送已受理' : '微信已受理' }}</span><StatusBadge v-else :value="notification.sendStatus" /></td><td><span v-if="notification.providerMode === 'mock' && ['DELIVERED', 'DELIVERY_FAILED'].includes(notification.deliveryStatus)" class="badge local">{{ notification.deliveryStatus === 'DELIVERED' ? '模拟送达' : '模拟送达失败' }}</span><StatusBadge v-else :value="notification.deliveryStatus" /></td><td>{{ date(notification.openedAt) }}</td><td><span v-if="notification.providerMode === 'mock'" class="badge local">LOCAL 模拟</span><span v-else>微信消息</span></td></tr>
    </tbody></table><div v-if="!notifications.length" class="empty compact">暂无培训通知，发布后的提醒状态会显示在这里。</div></div>
    <p class="small muted">发送受理、送达回执与员工打开通知分别记录。结果未知时不能视为已送达；员工仍可从小程序任务列表进入培训。</p>
  </section>
  <section class="panel">
    <div class="section-heading"><div><h2>提醒员工完成培训</h2><p class="muted small">选择需要跟进的员工，说明提醒原因并发送微信提醒。</p></div><span class="counter">已选 {{ personIds.length }} 人</span></div>
    <div v-if="!eligible.length" class="empty compact">{{ assignments.length ? '本次参训员工均已完成，无需再次提醒。' : '暂无可提醒的参训员工。' }}</div>
    <form v-else class="form-stack" @submit.prevent="remind">
      <div class="selection-list"><label v-for="assignment in eligible" :key="assignment.id" class="choice-row"><input type="checkbox" v-model="personIds" :value="assignment.personId" :data-testid="`remind-person-${assignment.personId}`" /><span><strong>{{ assignment.personName || assignment.name || '参训员工' }}</strong><small>{{ assignment.status === 'READY_TO_CONFIRM' ? '学习已达标，待本人确认' : assignment.status === 'NOT_STARTED' ? '尚未开始学习' : '尚未完成培训' }}</small></span></label></div>
      <label>提醒原因<input v-model="reason" required data-testid="reminder-reason" placeholder="例如：本周培训即将截止，请及时安排学习" /></label>
      <details><summary>上次送达结果未知，需要再次提醒</summary><p class="small muted">请让员工重新点击接收微信提醒，系统会检查每个人的新授权。再次发送可能重复送达，原通知的未知状态会保留。</p><label class="checkbox"><input type="checkbox" v-model="duplicate" data-testid="reminder-confirm-duplicate" />已了解可能重复送达，仍确认再次提醒</label></details>
      <div class="actions"><button class="primary" :disabled="busy || !personIds.length" data-testid="reminder-send">{{ busy ? '正在提交提醒…' : '发送培训提醒' }}</button><span class="small muted">微信提醒需员工已开通学习并授权接收通知。</span></div>
    </form>
  </section>
</template>
