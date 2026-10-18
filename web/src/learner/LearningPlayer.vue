<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, post } from '../services/api'
import { duration, percent } from '../services/format'
import { SessionTracker } from '../learning/sessionTracker'
import { resumePosition, visiblePageRange } from '../learning/geometry'
import type { JsonRecord } from '../types'
import NoticeBox from '../components/NoticeBox.vue'

const props = defineProps<{ assignmentId: string; material: JsonRecord }>()
const emit = defineEmits<{ back: []; updated: [] }>()
const video = ref<HTMLVideoElement>(), viewport = ref<HTMLElement>(), pageImage = ref<HTMLImageElement>()
const source = ref(''), pageNo = ref(1), rendered = ref(false), running = ref(false), buffering = ref(false)
const busy = ref(false), error = ref(''), mediaError = ref(false), syncState = ref(''), syncMessage = ref(''), progress = ref<JsonRecord>(props.material.progress || {})
const conflictSession = ref(''), sessionId = ref(''), videoLoaded = ref(false)
const isVideo = computed(() => props.material.type === 'MP4')
const pages = computed<JsonRecord[]>(() => props.material.pages || [])
const pageProgress = computed(() => {
  const pp = progress.value.pageProgress
  return Array.isArray(pp) ? pp.find(p => p.pageNo === pageNo.value) || {} : pp?.[pageNo.value] || {}
})
let tracker: SessionTracker | undefined
function focusMaterial() { const element = isVideo.value ? video.value : viewport.value; element?.scrollIntoView({ block: 'center' }) }

function payload(): JsonRecord {
  const visible = document.visibilityState === 'visible'
  if (isVideo.value) {
    const rect = video.value?.getBoundingClientRect()
    const visibleHeight = rect ? Math.max(0, Math.min(window.innerHeight, rect.bottom) - Math.max(0, rect.top)) : 0
    const playerVisible = !!rect && rect.height > 0 && visibleHeight >= rect.height / 2 && rect.right > 0 && rect.left < window.innerWidth
    return { positionMs: Math.round((video.value?.currentTime || 0) * 1000), playing: !!video.value && !video.value.paused && !video.value.ended && running.value, playbackRate: video.value?.playbackRate || 1, buffering: buffering.value, appVisible: visible, pageVisible: visible, playerVisible }
  }
  const page = pages.value.find(p => p.pageNo === pageNo.value), image = pageImage.value, box = viewport.value
  const rect = box?.getBoundingClientRect()
  const range = image && rect ? visiblePageRange({ top: Math.max(0, rect.top), bottom: Math.min(window.innerHeight, rect.bottom) }, image.getBoundingClientRect(), page?.height || image.naturalHeight) : null
  return { pageNo: pageNo.value, rendered: rendered.value && running.value && !!range, visibleTopPx: range?.[0] || 0, visibleBottomPx: range?.[1] || 0, appVisible: visible, pageVisible: visible, readerUnobscured: true }
}
async function loadSource(page = 1) {
  rendered.value = false; videoLoaded.value = false; mediaError.value = false; source.value = ''
  const ticket = await post(`/me/assignments/${props.assignmentId}/media-ticket`, { taskMaterialId: props.material.taskMaterialId || props.material.id, ...(isVideo.value ? {} : { pageNo: page }) })
  source.value = ticket.url
}
onMounted(async () => {
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('pagehide', hiding)
  if (!isVideo.value) {
    const states = progress.value.pageProgress || {}
    pageNo.value = pages.value.find(p => !(Array.isArray(states) ? states.find(s => s.pageNo === p.pageNo) : states[p.pageNo])?.requirementsMet)?.pageNo || 1
  }
  try { await loadSource(pageNo.value) } catch (e) { mediaError.value = true; error.value = (e as Error).message }
})
onBeforeUnmount(() => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hiding); tracker?.stop(); if (sessionId.value) void post(`/learning/sessions/${sessionId.value}/close`, { reason: 'PAGE_UNMOUNT' }).catch(() => {}) })

async function start(takeover = false) {
  busy.value = true; error.value = ''; conflictSession.value = ''
  try {
    const body = { assignmentId: props.assignmentId, taskMaterialId: props.material.taskMaterialId || props.material.id }
    const data = takeover && sessionId.value ? await post(`/learning/sessions/${sessionId.value}/takeover`, { ...body, expectedSessionId: sessionId.value }) : await post('/learning/sessions', body)
    tracker?.stop(); sessionId.value = data.sessionId; if (data.confirmedProgress) progress.value = data.confirmedProgress
    if (isVideo.value && video.value) { video.value.currentTime = resumePosition(progress.value) / 1000; video.value.playbackRate = 1 }
    focusMaterial()
    running.value = !isVideo.value
    tracker = new SessionTracker({ sessionId: data.sessionId, initialSeq: data.nextSeq, sampleType: isVideo.value ? 'VIDEO_SAMPLE' : 'DOC_SAMPLE', payload, onProgress(p) { progress.value = p; emit('updated') }, onPause() { running.value = false; video.value?.pause() }, onState(state, message) { syncState.value = state; if (message) syncMessage.value = message; else if (state === 'synced') syncMessage.value = ''; if (state === 'closed') sessionId.value = '' } })
    const baselineConfirmed = await tracker.start()
    if (baselineConfirmed && isVideo.value && video.value) { running.value = true; await video.value.play() }
  } catch (e) {
    if (e instanceof ApiError && e.code === 'SESSION_IN_USE') { conflictSession.value = e.details?.sessionId || ''; sessionId.value = conflictSession.value; error.value = '当前身份已有学习会话。你可以明确选择在此设备继续。' }
    else error.value = (e as Error).message
  } finally { busy.value = false }
}
function videoEvent(type: string) {
  if (type === 'PLAY') { if (!sessionId.value || syncState.value === 'offline') { video.value?.pause(); running.value = false; return }; running.value = true; buffering.value = false }
  if (type === 'WAITING') buffering.value = true
  if (type === 'PAUSE' || type === 'ENDED') running.value = false
  tracker?.event(type)
}
function hiding() { tracker?.event('HIDE'); running.value = false; video.value?.pause(); }
function visibility() { if (document.visibilityState === 'hidden') hiding(); else { tracker?.event('SHOW'); syncMessage.value = '已回到前台，请点击继续学习。' } }
async function resume() {
  if (!sessionId.value || syncState.value === 'closed') return start()
  await tracker?.flush()
  if (syncState.value !== 'synced') { syncMessage.value = '尚未恢复服务端确认，请检查网络后重试。'; return }
  focusMaterial()
  running.value = true; syncMessage.value = ''; tracker?.event(isVideo.value ? 'PLAY' : 'DOC_SAMPLE'); if (isVideo.value) await video.value?.play().catch(e => { error.value = e.message })
}
async function turnPage(delta: number) {
  tracker?.event('PAGE_LEAVE'); await tracker?.flush(); pageNo.value += delta
  try { await loadSource(pageNo.value); await nextTick(); if (viewport.value) viewport.value.scrollTop = 0 } catch (e) { error.value = (e as Error).message }
}
function imageLoaded() { rendered.value = true; if (sessionId.value) viewport.value?.scrollIntoView({ block: 'center' }); tracker?.event('PAGE_ENTER') }
function failedMedia() { rendered.value = false; mediaError.value = true; running.value = false; buffering.value = true; tracker?.event(isVideo.value ? 'WAITING' : 'PAGE_LEAVE'); video.value?.pause(); error.value = '材料加载失败或访问票据已过期。可重新获取访问地址，已确认进度会保留。' }
async function reloadMedia() { busy.value = true; running.value = false; video.value?.pause(); error.value = ''; try { await loadSource(pageNo.value) } catch (e) { mediaError.value = true; error.value = (e as Error).message } finally { busy.value = false } }
function videoReady() { videoLoaded.value = true; buffering.value = false; if (mediaError.value) { mediaError.value = false; error.value = '' }; if (video.value && sessionId.value) video.value.currentTime = resumePosition(progress.value) / 1000 }
function jumpMissing() {
  const page = pages.value.find(p => p.pageNo === pageNo.value), ranges: number[][] = pageProgress.value.ranges || []
  let missing = 0
  for (const range of ranges) { if (range[0] > missing) break; missing = Math.max(missing, range[1]) }
  if (viewport.value && pageImage.value && page?.height) viewport.value.scrollTop = Math.max(0, missing / page.height * pageImage.value.height - 20)
}
async function back() { busy.value = true; running.value = false; video.value?.pause(); await tracker?.finish(); tracker = undefined; sessionId.value = ''; emit('updated'); emit('back') }
</script>
<template>
  <section class="learning-view" data-testid="learning-player"><div class="learning-heading"><button class="link" @click="back" :disabled="busy" data-testid="learning-back">← 返回任务</button><span class="badge local">LOCAL 浏览器学习预览</span></div><h2>{{ material.title }}</h2><p class="small muted">{{ isVideo ? '按正常1倍速观看，跳过的片段需要补看。' : '逐页阅读，滚动查看完整内容。后台或未加载时不累计。' }}</p><NoticeBox :text="error" kind="error" /><NoticeBox :text="syncMessage" :kind="syncState === 'offline' ? 'error' : 'info'" />
    <button v-if="mediaError" class="secondary" :disabled="busy" @click="reloadMedia" data-testid="learning-media-retry">重新获取材料，保留进度</button>
    <div v-if="isVideo" class="video-container"><video ref="video" :src="source || undefined" controls playsinline preload="metadata" controlslist="nodownload noplaybackrate noremoteplayback" disablepictureinpicture data-testid="learning-video" @loadedmetadata="videoReady" @play="videoEvent('PLAY')" @pause="videoEvent('PAUSE')" @waiting="videoEvent('WAITING')" @playing="buffering = false" @ended="videoEvent('ENDED')" @seeking="videoEvent('SEEK')" @ratechange="() => { if (video) video.playbackRate = 1 }" @error="failedMedia"></video></div>
    <template v-else><div class="pdf-toolbar"><button :disabled="pageNo === 1 || busy" @click="turnPage(-1)" data-testid="pdf-prev">上一页</button><strong data-testid="pdf-page-number">第 {{ pageNo }} / {{ material.pageCount }} 页</strong><button :disabled="pageNo >= material.pageCount || busy" @click="turnPage(1)" data-testid="pdf-next">下一页</button></div><div ref="viewport" class="pdf-reader" data-testid="pdf-reader"><img v-if="source" ref="pageImage" :src="source" :alt="`${material.title}第${pageNo}页`" @load="imageLoaded" @error="failedMedia" data-testid="learning-pdf-page"></div><p class="small muted">本页已确认 {{ duration(pageProgress.effectiveMs || pageProgress.visibleMs || 0) }} · 最低 {{ duration(material.rule?.minPageVisibleMs || 30000) }}</p><button v-if="!pageProgress.requirementsMet" class="link" @click="jumpMissing">定位到本页尚未确认区域</button><span v-else class="small text-success">本页已达到学习要求</span></template>
    <div class="learning-status"><div><span class="eyebrow">当前学习进度</span><h3 data-testid="learning-progress">{{ progress.requirementsMet ? '已达到本材料学习要求' : `${percent(progress.coverageRatio)} 覆盖` }}</h3><p class="small muted">有效学习 {{ duration(progress.effectiveMs) }} · {{ syncState === 'synced' ? '进度已同步' : syncState === 'syncing' ? '正在同步…' : '等待同步' }}</p></div><div class="actions"><button v-if="conflictSession" class="primary" @click="start(true)" data-testid="learning-takeover">在此设备继续</button><button v-else-if="!sessionId" class="primary" :disabled="busy || !source || (isVideo ? !videoLoaded : !rendered)" @click="start()" data-testid="learning-start">{{ busy ? '正在连接…' : '开始学习' }}</button><button v-else-if="!running && !progress.requirementsMet" class="primary" @click="resume" data-testid="learning-resume">继续学习</button><button v-if="progress.requirementsMet" class="primary" @click="back" data-testid="learning-finish">返回任务</button></div></div><div class="progress-track"><span :style="{ width: percent(progress.coverageRatio || 0) }"></span></div><p v-if="progress.missingRangesMs?.length && isVideo" class="small muted">待补看片段：{{ progress.missingRangesMs.map((r: number[]) => `${duration(r[0])}–${duration(r[1])}`).join('、') }}</p>
  </section>
</template>
