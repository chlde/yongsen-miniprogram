<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get, post } from '../services/api'
import { useFeedback } from '../composables/useFeedback'
import type { JsonRecord } from '../types'
import NoticeBox from '../components/NoticeBox.vue'

const items = ref<JsonRecord[]>([])
const loaded = ref(false)
const offset = ref(0)
const hasMore = ref(false)
const pageSize = 100
const { busy, error, success, run } = useFeedback()
const labels: Record<string, string> = { PENDING: '等待处理', QUEUED: '等待处理', DISPATCHED: '已排队', RUNNING: '正在处理', RETRY: '等待重试', FAILED: '处理失败', DONE: '已处理' }
async function load(nextOffset = offset.value) {
  const result = await get(`/admin/background-work?limit=${pageSize}&offset=${nextOffset}`)
  items.value = result.items || []
  offset.value = nextOffset
  hasMore.value = result.hasMore === true
  loaded.value = true
}
function retryable(item: JsonRecord) {
  return item.status !== 'DONE' && (item.status !== 'RUNNING' || Number(item.leaseUntilMs || 0) <= Date.now())
}
async function retry(item: JsonRecord) {
  await run(async () => {
    await post(`/admin/background-work/${encodeURIComponent(item.id)}/retry`)
    await load()
  }, '已提交重新处理，请刷新查看处理结果。')
}
onMounted(() => run(load))
</script>

<template>
  <section class="panel" data-testid="background-work-panel">
    <div class="section-heading"><div><h2>培训处理状态</h2><p class="muted small">优先显示未完成的教材和通知处理，修复问题后可重新处理失败任务。</p></div><button class="secondary" :disabled="busy" @click="run(() => load(0))">刷新处理状态</button></div>
    <NoticeBox :text="error" kind="error" /><NoticeBox :text="success" kind="success" />
    <p v-if="loaded && !items.length" class="muted">暂无处理记录。</p>
    <div v-if="items.length" class="table-wrap"><table><thead><tr><th>类型</th><th>状态</th><th>处理次数</th><th>异常编号</th><th>操作</th></tr></thead><tbody>
      <tr v-for="item in items" :key="item.id"><td>{{ item.kind === 'MEDIA' ? '教材处理' : '任务通知' }}</td><td>{{ labels[item.status] || item.status }}</td><td>{{ item.attempts }}</td><td>{{ item.errorCode || '—' }}</td><td><button v-if="retryable(item)" class="secondary" :disabled="busy" @click="retry(item)">重新处理</button><span v-else>—</span></td></tr>
    </tbody></table></div>
    <div v-if="offset > 0 || hasMore" class="section-heading">
      <span class="muted small">第 {{ offset / pageSize + 1 }} 页</span>
      <div><button class="secondary" :disabled="busy || offset === 0" @click="run(() => load(Math.max(0, offset - pageSize)))">上一页</button> <button class="secondary" :disabled="busy || !hasMore" @click="run(() => load(offset + pageSize))">下一页</button></div>
    </div>
  </section>
</template>
