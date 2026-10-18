<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getAll, post } from '../services/api'
import { useFeedback } from '../composables/useFeedback'
import type { JsonRecord } from '../types'
import NoticeBox from '../components/NoticeBox.vue'

const cards = ref<JsonRecord[]>([])
const { busy, error, success, run } = useFeedback()
let poll: ReturnType<typeof setInterval>
let loading = false
async function refresh() { if (loading) return; loading = true; try { cards.value = await getAll('/local/outbox') } finally { loading = false } }
onMounted(() => { void run(refresh); poll = setInterval(() => { void refresh().catch(() => {}) }, 2000) })
onUnmounted(() => clearInterval(poll))
async function deliver(card: JsonRecord, result: string) { await run(async () => { await post(`/local/notifications/${card.notificationId}/deliver`, { result }); await refresh() }, result === 'DELIVERED' ? '已模拟该通知送达，仅用于本地测试。' : '已模拟该通知送达失败，仅用于本地测试。') }
function entry(card: JsonRecord) { return `${location.origin}/#learner?assignmentId=${encodeURIComponent(card.assignmentId)}&notificationId=${encodeURIComponent(card.notificationId)}` }
function copyEntry(card: JsonRecord) { void run(async () => navigator.clipboard.writeText(entry(card)), '测试任务入口已复制，请以对应测试学员身份打开。') }
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">LOCAL TESTING</p><h1>培训通知测试</h1><p>在本地检查培训通知与任务入口，辅助上线前验证。</p></div><button class="secondary" :disabled="busy" @click="run(refresh)" data-testid="outbox-refresh">刷新测试通知</button></div>
  <div class="local-callout"><strong>仅限 LOCAL 本地测试</strong><p>这里的卡片和送达结果均来自模拟服务，不表示真实个人微信已经收到消息。正式上线必须使用真实微信完成收件验证。</p></div>
  <NoticeBox :text="error" kind="error" /><NoticeBox :text="success" kind="success" />
  <div class="section-heading"><h2>已生成的测试通知</h2><span class="counter">{{ cards.length }} 条</span></div>
  <div class="card-grid"><section v-for="card in cards" :key="card.id" class="panel local-card" data-testid="outbox-card"><span class="badge local">LOCAL 模拟通知</span><h2>{{ card.title }}</h2><p class="muted small">请及时安排学习，完成本次培训要求。</p><div class="actions"><button class="secondary" :disabled="busy" @click="deliver(card, 'DELIVERED')" data-testid="outbox-deliver">模拟收到通知</button><button class="link" :disabled="busy" @click="deliver(card, 'DELIVERY_FAILED')">模拟送达失败</button></div><label class="compact-field">测试学员的培训入口<input readonly :value="entry(card)" data-testid="outbox-entry" /></label><button class="link" @click="copyEntry(card)">复制测试入口</button><details><summary>测试通知详情</summary><p class="small muted">通知编号：{{ card.notificationId }}</p></details></section></div>
  <div v-if="!cards.length" class="panel empty"><h3>暂无测试通知</h3><p>完成测试学员身份关联和订阅授权后，发布一项培训即可查看对应通知。</p></div>
</template>
