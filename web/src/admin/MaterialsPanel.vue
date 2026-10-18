<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { get, getAll, post } from '../services/api'
import { duration } from '../services/format'
import { useFeedback } from '../composables/useFeedback'
import type { Material } from '../types'
import NoticeBox from '../components/NoticeBox.vue'
import StatusBadge from '../components/StatusBadge.vue'

const materials = ref<Material[]>([])
const limits = ref<{ videoMaxBytes: number; pdfMaxBytes: number } | null>(null)
const title = ref('')
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement>()
const selected = ref<Material | null>(null)
const previewUrl = ref('')
const pageNo = ref(1)
const checked = ref(false)
const uploadPhase = ref('')
const query = ref('')
const formatFilter = ref('ALL')
const { busy, error, success, run } = useFeedback()
const usableCount = computed(() => materials.value.filter(material => material.status === 'READY' && material.compatibilityChecked === true).length)
const filteredMaterials = computed(() => materials.value.filter(material => (formatFilter.value === 'ALL' || material.type === formatFilter.value) && material.title.toLowerCase().includes(query.value.trim().toLowerCase())))
let poll: ReturnType<typeof setInterval>
async function refresh() { materials.value = await getAll<Material>('/admin/materials') }
onMounted(() => { void run(async () => { await Promise.all([refresh(), get<{ videoMaxBytes: number; pdfMaxBytes: number }>('/admin/materials/upload-limits').then(value => { limits.value = value })]) }); poll = setInterval(() => { if (materials.value.some(material => material.status === 'PROCESSING')) refresh().catch(() => {}) }, 2000) })
onUnmounted(() => clearInterval(poll))
function choose(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] || null; if (file.value && !title.value) title.value = file.value.name.replace(/\.[^.]+$/, '') }
async function upload() {
  if (!file.value) { error.value = '请选择要上传的视频或文档。'; return }
  await run(async () => {
    const selectedFile = file.value!
    const type = selectedFile.name.toLowerCase().endsWith('.pdf') ? 'PDF' : 'MP4'
    if (!limits.value) throw new Error('尚未取得上传限制，请刷新页面后重试。')
    const maximum = type === 'PDF' ? limits.value.pdfMaxBytes : limits.value.videoMaxBytes
    if (selectedFile.size > maximum) throw new Error(`本环境单份${type === 'PDF' ? '文档' : '视频'}最多 ${(maximum / 1024 / 1024).toFixed(0)} MiB，请先拆分或压缩材料。`)
    uploadPhase.value = '正在准备…'
    const data = await post('/admin/materials/uploads', { title: title.value, type, size: selectedFile.size, filename: selectedFile.name })
    uploadPhase.value = '正在上传…'
    const response = await fetch(data.uploadUrl, { method: data.uploadMethod || 'PUT', body: selectedFile, headers: data.uploadHeaders || { 'Content-Type': selectedFile.type || 'application/octet-stream' } })
    if (!response.ok) throw new Error(`文件上传失败（${response.status}），请稍后重试。`)
    uploadPhase.value = '正在处理文件…'
    await post(`/admin/materials/${data.materialId || data.id}/upload-complete`)
    await refresh()
    title.value = ''
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
  }, '材料已上传。处理完成后，请预览内容并确认可供员工学习。')
  uploadPhase.value = ''
}
async function preview(material: Material, page = 1) { await run(async () => { selected.value = material; pageNo.value = page; checked.value = false; previewUrl.value = ''; const ticket = await post(`/admin/materials/${material.id}/media-ticket`, material.type === 'PDF' ? { pageNo: page } : {}); previewUrl.value = ticket.url }) }
async function approve() { if (!selected.value || !checked.value) return; await run(async () => { await post(`/admin/materials/${selected.value!.id}/compatibility-check`, { passed: true }); selected.value = null; previewUrl.value = ''; await refresh() }, '材料已确认，现在可以加入培训任务。') }
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">TRAINING RESOURCES</p><h1>培训资源</h1><p>汇集安全作业规范、岗位操作指导与员工成长材料，为培训提供持续支持。</p></div><button class="secondary" :disabled="busy" @click="run(refresh)">更新资源库</button></div>
  <NoticeBox :text="error" kind="error" /><NoticeBox :text="success" kind="success" />
  <div class="stats-grid"><div class="stat"><span>培训材料</span><strong>{{ materials.length }}</strong></div><div class="stat"><span>可用资源</span><strong>{{ usableCount }}</strong></div><div class="stat"><span>待预览确认</span><strong>{{ materials.filter(material => material.status === 'READY' && material.compatibilityChecked !== true).length }}</strong></div></div>
  <section class="panel">
    <div class="section-heading"><div><h2>添加培训材料</h2><p class="muted small">上传企业已有的视频或文档，预览确认后即可安排员工学习。</p></div><span class="badge">视频 · 文档</span></div>
    <form class="upload-form" @submit.prevent="upload"><label>培训材料名称<input v-model="title" required placeholder="例如：高处作业安全操作指导" data-testid="material-title" /></label><label>选择视频或文档<input ref="fileInput" type="file" accept=".mp4,.pdf,video/mp4,application/pdf" @change="choose" required data-testid="material-file" /></label><button class="primary" :disabled="busy || !limits" data-testid="material-upload">{{ busy ? uploadPhase || '处理中…' : '上传材料' }}</button></form>
    <p v-if="limits" class="small muted" data-testid="material-upload-limits">本环境单份视频最多 {{ limits.videoMaxBytes / 1024 / 1024 }} MiB，PDF 最多 {{ limits.pdfMaxBytes / 1024 / 1024 }} MiB。</p>
    <p class="small muted">支持 MP4 视频与 PDF 文档，Word / PPT 请先转成 PDF。请上传可在手机正常播放的视频；更新教材时另传一份新材料。</p>
  </section>
  <section v-if="selected" class="panel preview-panel" data-testid="material-preview">
    <div class="section-heading"><div><p class="eyebrow">CONTENT PREVIEW</p><h2>{{ selected.title }}</h2><p class="muted small">检查内容清晰、完整，确保员工能正常观看或阅读。</p></div><button class="link" @click="selected = null; previewUrl = ''">收起预览</button></div>
    <video v-if="selected.type === 'MP4' && previewUrl" :src="previewUrl" controls preload="metadata" class="material-video" data-testid="material-preview-video"></video>
    <template v-else-if="previewUrl"><div class="pdf-preview"><img :src="previewUrl" :alt="`${selected.title}，第 ${pageNo} 页`" data-testid="material-preview-page" /></div><div class="pagination"><button :disabled="pageNo <= 1 || busy" @click="preview(selected, pageNo - 1)">上一页</button><span>第 {{ pageNo }} / {{ selected.pageCount }} 页</span><button :disabled="pageNo >= (selected.pageCount || 1) || busy" @click="preview(selected, pageNo + 1)">下一页</button></div></template>
    <div class="approval-bar"><label class="checkbox"><input v-model="checked" type="checkbox" data-testid="material-compatibility-checkbox" />已检查内容完整、清晰，可在目标设备正常学习</label><button class="primary" :disabled="!checked || busy" @click="approve" data-testid="material-approve">确认可用于培训</button></div>
    <details v-if="selected.sha256"><summary>材料版本信息</summary><p class="small muted">文件摘要用于区分材料版本：{{ selected.sha256 }}</p></details>
  </section>
  <section class="panel">
    <div class="section-heading"><div><h2>企业资源库</h2><p class="muted small">管理已上传的培训材料，随时查看内容与可用状态。</p></div><div class="actions"><input v-model="query" class="search" placeholder="搜索材料名称" aria-label="搜索培训材料" /><select v-model="formatFilter" aria-label="材料类型"><option value="ALL">全部材料</option><option value="MP4">培训视频</option><option value="PDF">学习文档</option></select></div></div>
    <div class="table-wrap"><table><thead><tr><th>培训材料</th><th>内容规格</th><th>资源状态</th><th>预览确认</th><th>操作</th></tr></thead><tbody>
      <tr v-for="material in filteredMaterials" :key="material.id" :data-testid="`material-row-${material.title}`"><td><strong>{{ material.title }}</strong><span v-if="material.reason || material.error || material.errorMessage" class="cell-sub text-danger">{{ material.reason || material.error || material.errorMessage }}</span><span v-else class="cell-sub">{{ material.type === 'MP4' ? '培训视频' : '学习文档' }}</span></td><td>{{ material.type }}<span class="cell-sub">{{ material.type === 'PDF' ? `${material.pageCount || 0} 页` : duration(material.durationMs) }}</span></td><td><span v-if="material.status === 'READY' && material.compatibilityChecked === true" class="badge active">可用于培训</span><StatusBadge v-else :value="material.status" /></td><td>{{ material.compatibilityChecked === true ? '已确认' : '待预览确认' }}</td><td><button class="link" :disabled="material.status !== 'READY'" @click="preview(material)" data-testid="material-preview-button">预览材料</button></td></tr>
    </tbody></table><div v-if="!materials.length" class="empty"><h3>建立企业的培训资源库</h3><p>上传现有材料，为班组安全学习、岗位技能与员工成长培训做好准备。</p></div><div v-else-if="!filteredMaterials.length" class="empty compact">没有匹配的培训材料，请调整搜索条件。</div></div>
  </section>
</template>
