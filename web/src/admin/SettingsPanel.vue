<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get, post, put, revision } from '../services/api'
import { useFeedback } from '../composables/useFeedback'
import type { JsonRecord } from '../types'
import NoticeBox from '../components/NoticeBox.vue'
import BackgroundWorkPanel from './BackgroundWorkPanel.vue'

const config = ref<JsonRecord>({})
const templateId = ref('')
const mapping = ref('{"title":"thing1","dueAt":"time2"}')
const health = ref<JsonRecord | null>(null)
const { busy, error, success, run } = useFeedback()
function currentFields(): JsonRecord { try { const fields = JSON.parse(mapping.value); return fields && typeof fields === 'object' && !Array.isArray(fields) ? fields : {} } catch { return {} } }
function setField(key: string, value: string) { const fields = currentFields(); if (value.trim()) fields[key] = value.trim(); else delete fields[key]; mapping.value = JSON.stringify(fields, null, 2) }
const titleField = computed({ get: () => String(currentFields().title || ''), set: value => setField('title', value) })
const dueField = computed({ get: () => String(currentFields().dueAt || ''), set: value => setField('dueAt', value) })
const mock = computed(() => (health.value?.providerMode || config.value.providerMode) === 'mock')
onMounted(() => run(async () => { config.value = await get('/admin/notification-config'); templateId.value = config.value.templateId || ''; mapping.value = JSON.stringify(config.value.fieldMapping || { title: 'thing1', dueAt: 'time2' }, null, 2) }))
async function save() {
  await run(async () => {
    let fields
    try { fields = JSON.parse(mapping.value) } catch { throw new Error('模板内容对应关系格式不正确，请检查后保存。') }
    await put('/admin/notification-config', { templateId: templateId.value, fieldMapping: fields, expectedRevision: revision(config.value) })
    config.value = await get('/admin/notification-config')
    health.value = null
  }, '培训通知设置已保存。')
}
async function check() { await run(async () => { health.value = await post('/admin/notification-config/check') }) }
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">TRAINING SETTINGS</p><h1>培训通知设置</h1><p>配置企业的微信培训提醒，让员工及时收到学习安排。</p></div></div>
  <NoticeBox :text="error" kind="error" /><NoticeBox :text="success" kind="success" />
  <NoticeBox v-if="config.providerMode === 'real' && !config.configured" text="尚未配置微信培训提醒模板。员工可以登录并学习；通知会等待模板配置与员工授权，不代表已经发送。" />
  <div class="two-columns">
    <section class="panel">
      <div class="section-heading"><div><h2>微信提醒模板</h2><p class="muted small">由负责微信账号的管理员，按企业已批准的订阅消息模板填写。</p></div></div>
      <form class="form-stack" @submit.prevent="save">
        <label>订阅消息模板 ID<input v-model="templateId" required data-testid="template-id" placeholder="填写微信公众平台中的模板 ID" /></label>
        <div class="form-row"><label>培训名称对应字段<input v-model="titleField" placeholder="例如：thing1" /><small>将培训任务名称填入模板的标题字段。</small></label><label>截止时间对应字段<input v-model="dueField" placeholder="例如：time2" /><small>模板要求该项时，发布培训须填写截止时间。</small></label></div>
        <details><summary>高级配置：编辑字段对应关系</summary><label>模板字段配置<textarea v-model="mapping" rows="5" class="code-input" data-testid="template-mapping"></textarea></label></details>
        <div class="actions"><button class="primary" :disabled="busy" data-testid="template-save">保存通知设置</button><button type="button" class="secondary" :disabled="busy" @click="check" data-testid="template-check">检查当前配置</button></div>
      </form>
    </section>
    <section class="panel">
      <div class="section-heading"><div><h2>通知服务状态</h2><p class="muted small">检查配置后，仍需通过指定员工的真实微信验证通知收件。</p></div></div>
      <div v-if="mock" class="local-callout"><strong>LOCAL · 当前为模拟通知</strong><p>本地测试不会向真实个人微信发送消息。正式使用前，需要同一企业 AppID、批准模板和真实手机收件验证。</p></div>
      <div v-if="health" class="form-stack"><div class="list-item"><span>模板设置</span><span class="badge" :class="health.configured ? 'active' : 'pending'">{{ health.configured ? '已填写模板' : '尚未配置' }}</span></div><div class="list-item"><span>发送渠道</span><strong>{{ health.providerMode === 'mock' ? '本地模拟' : '微信订阅消息' }}</strong></div><div class="list-item"><span>真实微信送达</span><span class="badge" :class="health.liveDeliveryVerified ? 'active' : 'pending'">{{ health.liveDeliveryVerified ? '已完成验证' : '仍需真机验证' }}</span></div></div>
      <p v-else class="muted">点击“检查当前配置”查看已保存的设置。这项检查不会向员工发送通知。</p>
      <p v-if="health && !mock && !health.callbackConfigured" class="small text-danger">消息回调尚未配置，无法接收微信送达回执。请由运维人员完成回调地址、Token 和加密密钥设置。</p>
      <p class="small muted">检查配置不等于通知已经送达。发送还需员工开通学习、主动授权，以及账号模板可用。账号密钥由系统运维人员保管，不在此页面展示。</p>
    </section>
  </div>
  <BackgroundWorkPanel />
</template>
