<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAll, patch, post, revision } from '../services/api'
import { date } from '../services/format'
import { useFeedback } from '../composables/useFeedback'
import type { JsonRecord, Person } from '../types'
import NoticeBox from '../components/NoticeBox.vue'
import StatusBadge from '../components/StatusBadge.vue'

const people = ref<Person[]>([])
const bindings = ref<JsonRecord[]>([])
const invitation = ref<JsonRecord | null>(null)
const form = ref({ personNo: '', name: '', unit: '', project: '' })
const editing = ref<Person | null>(null)
const query = ref('')
const { busy, error, success, run } = useFeedback()
const pendingCount = computed(() => bindings.value.filter(binding => binding.status === 'PENDING').length)
const filteredPeople = computed(() => people.value.filter(person => `${person.name} ${person.personNo} ${person.unit || ''} ${person.project || ''}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const reviewBindings = computed(() => [...bindings.value].sort((left, right) => Number(right.status === 'PENDING') - Number(left.status === 'PENDING')))
async function refresh() { const [personList, bindingList] = await Promise.all([getAll<Person>('/admin/people'), getAll('/admin/bindings')]); people.value = personList; bindings.value = bindingList }
onMounted(() => run(refresh))
function clearForm() { form.value = { personNo: '', name: '', unit: '', project: '' }; editing.value = null }
async function save() { await run(async () => { if (editing.value) await patch(`/admin/people/${editing.value.id}`, { ...form.value, expectedRevision: revision(editing.value) }); else await post('/admin/people', form.value); clearForm(); await refresh() }, '员工信息已保存。') }
function edit(person: Person) { editing.value = person; form.value = { personNo: person.personNo, name: person.name, unit: person.unit || '', project: person.project || '' } }
async function invite(person: Person) { await run(async () => { invitation.value = { ...await post(`/admin/people/${person.id}/invitation`), name: person.name }; await refresh() }) }
async function decide(binding: JsonRecord, decision: string) { await run(async () => { await post(`/admin/bindings/${binding.id}/decision`, { decision, reason: decision === 'APPROVE' ? '管理员已通过现有联系渠道核对人员身份' : '管理员核对未通过', expectedRevision: revision(binding) }); await refresh() }, decision === 'APPROVE' ? '身份核对完成，员工可以进入微信学习。' : '本次申请未通过核对。') }
async function toggle(person: Person) { await run(async () => { await patch(`/admin/people/${person.id}`, { status: person.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE', expectedRevision: revision(person), reason: '管理员调整人员状态' }); await refresh() }, '员工参训状态已更新。') }
async function revoke(binding: JsonRecord) { if (!confirm('解除后，原微信将无法继续学习。已有记录保留，员工需要重新申请并核对身份。是否继续？')) return; await run(async () => { await post(`/admin/bindings/${binding.id}/revoke`, { reason: '管理员发起换绑，需重新邀请并核对' }); await refresh() }, '原微信已解除关联，可为员工重新生成邀请码。') }
async function copyInvite() { if (invitation.value) await run(async () => navigator.clipboard.writeText(invitation.value!.inviteCode), '邀请码已复制，请单独发给该员工本人。') }
function accountState(personId: string) { if (bindings.value.some(binding => binding.personId === personId && binding.status === 'ACTIVE')) return '已开通'; return bindings.value.some(binding => binding.personId === personId && binding.status === 'PENDING') ? '待核对' : '未开通' }
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">EMPLOYEE MANAGEMENT</p><h1>员工管理</h1><p>维护参训员工与所属项目，帮助每位员工便捷地开始微信学习。</p></div><button class="secondary" :disabled="busy" @click="run(refresh)" data-testid="people-refresh">更新员工信息</button></div>
  <NoticeBox :text="error" kind="error" /><NoticeBox :text="success" kind="success" />
  <div class="stats-grid"><div class="stat"><span>员工总数</span><strong>{{ people.length }}</strong></div><div class="stat"><span>有效参训员工</span><strong>{{ people.filter(person => person.status === 'ACTIVE').length }}</strong></div><div class="stat"><span>待核对申请</span><strong>{{ pendingCount }}</strong></div></div>
  <section v-if="invitation" class="panel invitation" data-testid="invitation-result"><div><h3>{{ invitation.name }}的学习邀请码</h3><code data-testid="invite-code">{{ invitation.inviteCode }}</code><p class="small muted">请单独发给本人，在 {{ date(invitation.expiresAt) }} 前使用。邀请码仅在本次生成后展示，提交申请后即失效。</p></div><div class="actions"><button class="primary" @click="copyInvite">复制邀请码</button><button class="link" @click="invitation = null">收起</button></div></section>
  <div class="two-columns">
    <section class="panel">
      <div class="section-heading"><div><h2>{{ editing ? '编辑员工信息' : '添加参训员工' }}</h2><p class="muted small">员工编号用于区分人员，姓名与所属信息请保持准确。</p></div></div>
      <form @submit.prevent="save" class="form-stack"><label>员工编号<input v-model="form.personNo" :disabled="!!editing" required data-testid="person-number" placeholder="填写工号或企业内部人员编号" /></label><label>员工姓名<input v-model="form.name" required data-testid="person-name" placeholder="填写真实姓名" /></label><div class="form-row"><label>所属单位<input v-model="form.unit" data-testid="person-unit" placeholder="例如：作业服务部" /></label><label>所属项目<input v-model="form.project" data-testid="person-project" placeholder="填写员工所属项目" /></label></div><div class="actions"><button class="primary" :disabled="busy" data-testid="person-save">{{ editing ? '保存员工信息' : '添加员工' }}</button><button v-if="editing" type="button" class="secondary" @click="clearForm">取消编辑</button></div></form>
    </section>
    <section class="panel">
      <div class="section-heading"><div><h2>开通微信学习</h2><p class="muted small">员工使用邀请码申请后，请通过已有联系方式核实本人，再开通学习。</p></div><span class="counter">{{ pendingCount }} 待核对</span></div>
      <div v-if="!bindings.length" class="empty compact">暂无员工申请。可在下方名册生成个人邀请码。</div>
      <div class="selection-list"><div v-for="binding in reviewBindings" :key="binding.id" class="list-item" :data-testid="`binding-${binding.personName || binding.personId}`"><div><strong>{{ binding.personName || people.find(person => person.id === binding.personId)?.name || '待核对员工' }}</strong><p class="small muted">申请时间 {{ date(binding.createdAt) }}</p><StatusBadge :value="binding.status" /></div><div class="actions"><template v-if="binding.status === 'PENDING'"><button class="primary small-button" :disabled="busy" @click="decide(binding, 'APPROVE')" data-testid="binding-approve">核对通过</button><button class="text-danger" :disabled="busy" @click="decide(binding, 'REJECT')">不予通过</button></template><button v-else-if="binding.status === 'ACTIVE'" class="link" :disabled="busy" @click="revoke(binding)">解除 / 更换微信</button></div></div></div>
    </section>
  </div>
  <section class="panel">
    <div class="section-heading"><div><h2>员工名册</h2><p class="muted small">查看人员信息、开通学习账号或调整参训状态。</p></div><input class="search" v-model="query" placeholder="搜索姓名、编号、单位或项目" aria-label="搜索员工" /></div>
    <div class="table-wrap"><table><thead><tr><th>员工</th><th>单位 / 项目</th><th>参训状态</th><th>微信学习</th><th>人员管理</th></tr></thead><tbody>
      <tr v-for="person in filteredPeople" :key="person.id" :data-testid="`person-row-${person.personNo}`"><td><strong>{{ person.name }}</strong><span class="cell-sub">{{ person.personNo }}</span></td><td>{{ person.unit || '—' }}<span class="cell-sub">{{ person.project || '—' }}</span></td><td><span v-if="person.status === 'ACTIVE'" class="badge active">可参训</span><StatusBadge v-else :value="person.status" /></td><td>{{ accountState(person.id) }}</td><td><div class="actions"><button class="link" @click="invite(person)" :disabled="busy || person.status !== 'ACTIVE'" data-testid="person-invite">生成邀请码</button><button class="link" @click="edit(person)">编辑</button><button class="link muted" @click="toggle(person)" :disabled="busy">{{ person.status === 'ACTIVE' ? '停用' : '启用' }}</button></div></td></tr>
    </tbody></table><div v-if="!people.length" class="empty">还没有参训员工，请先添加人员信息。</div><div v-else-if="!filteredPeople.length" class="empty compact">没有找到匹配员工，请调整搜索条件。</div></div>
  </section>
</template>
