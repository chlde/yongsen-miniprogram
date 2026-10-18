<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../services/api'
import AppIcon from '../components/AppIcon.vue'

type TutorialStatus = 'NOT_STARTED' | 'DISMISSED' | 'COMPLETED'
type SavedStatus = Exclude<TutorialStatus, 'NOT_STARTED'>
interface TutorialState { tutorialId: string; status: TutorialStatus; updatedAt: string | null }
interface TourStep {
  key: string
  target: string
  entry: string
  icon: string
  title: string
  description: string
  actions: { label: string; detail: string }[]
  note: string
}

const emit = defineEmits<{ openChange: [value: boolean] }>()
const tutorialId = 'admin-basics-v1'
const steps: TourStep[] = [
  {
    key: 'welcome', target: 'overview', entry: '培训工作台', icon: 'shield',
    title: '从这里开始，安排一次企业培训',
    description: '用 5 个操作步骤，了解员工开通、材料准备、通知配置、培训发布与学习跟进。',
    actions: [
      { label: '找到高亮的导航入口', detail: '每一屏都会指出要点击的位置，并说明进入后怎么操作。' },
      { label: '按“下一步”了解完整流程', detail: '即使还没有员工和材料，也可以先看完教程。' },
      { label: '准备好后再开始办理', detail: '教程不会切换当前页面，也不会提交表单或发送通知。' },
    ],
    note: '随时点击页面上方“使用教程”重新查看；当前未提交的表单会保留。',
  },
  {
    key: 'people', target: 'people', entry: '员工管理', icon: 'users',
    title: '添加员工，开通微信学习',
    description: '先准备参训名册，再帮助员工把个人微信与正确身份关联。',
    actions: [
      { label: '点击“员工管理”→“添加员工”', detail: '填写员工编号、真实姓名及所属信息。' },
      { label: '在员工名册点击“生成邀请码”', detail: '将个人邀请码单独发给本人，由员工在微信小程序中登录并提交。' },
      { label: '在“开通微信学习”点击“核对通过”', detail: '通过已有联系方式核实本人后再操作；添加名册不等于已开通微信。' },
    ],
    note: '如暂未收到申请，可稍后回到这里点击“更新员工信息”。',
  },
  {
    key: 'materials', target: 'materials', entry: '培训资源', icon: 'folder',
    title: '上传材料，预览后再用于培训',
    description: '使用企业已有的安全生产、岗位技能与员工能力培训材料。',
    actions: [
      { label: '点击“培训资源”→“上传材料”', detail: '填写材料名称，选择 MP4 视频或 PDF 文档。Word、PPT 请先转为 PDF。' },
      { label: '处理完成后点击“预览材料”', detail: '检查视频能正常播放、文档各页内容完整清晰。' },
      { label: '勾选已检查，再点击“确认可用于培训”', detail: '完成预览确认的资源，才会出现在发布培训的可选材料中。' },
    ],
    note: '首页培训方向用于帮助起草名称和目标，实际材料仍需由管理员上传、选择。',
  },
  {
    key: 'notifications', target: 'settings', entry: '通知设置', icon: 'bell',
    title: '发布前，准备微信培训提醒',
    description: '由负责企业微信小程序账号的管理员配置已批准的通知模板。',
    actions: [
      { label: '点击“通知设置”', detail: '填写订阅消息模板 ID，以及培训名称、截止时间对应的实际字段。' },
      { label: '点击“保存通知设置”→“检查当前配置”', detail: '检查不会发送通知。模板要求截止时间时，发布培训也须填写截止时间。' },
      { label: '请员工在小程序主动接收微信提醒', detail: '通知还需要身份开通、员工授权和可用模板；正式使用前需验证真实微信收件。' },
    ],
    note: '本地预览只模拟通知。配置检查通过，不表示员工已经收到微信通知。',
  },
  {
    key: 'publish', target: 'tasks', entry: '培训任务', icon: 'book',
    title: '选择内容与员工，发布培训',
    description: '将学习材料安排给指定员工，让他们在微信中领取自己的任务。',
    actions: [
      { label: '点击“培训任务”→“发布培训”', detail: '填写培训名称、目标与要求，并按需设置完成截止时间。' },
      { label: '选择培训材料和参训员工', detail: '至少选择 1 份可用材料与 1 位员工；有 PDF 时请设定每页最低阅读时间。' },
      { label: '检查无误，点击“发布培训任务”', detail: '发布后材料、人员和学习要求固定；未开通微信或未授权的员工，通知会等待相应条件。' },
    ],
    note: '本教程不会替你发布。关闭教程后，请按实际培训安排填写并检查。',
  },
  {
    key: 'progress', target: 'tasks', entry: '培训任务', icon: 'growth',
    title: '跟进学习，查看培训记录',
    description: '发布后回到培训列表，持续了解每位员工的参与情况。',
    actions: [
      { label: '点击“培训任务”→“管理培训”', detail: '在“员工学习进度”中点击“学习详情”，查看视频与文档的学习情况。' },
      { label: '点击“培训通知与提醒”跟进通知', detail: '分别查看发送、送达与从通知进入的状态；需要时再选择人员进行提醒。' },
      { label: '点击“导出培训记录”或“打印记录”', detail: '导出整项培训的 CSV，或查看单名员工的可打印记录。' },
    ],
    note: '学习完成表示达到本次学习要求并由本人确认；如培训要求考试或实操，还需另行完成。',
  },
]

const loaded = ref(false), loadFailed = ref(false), open = ref(false), stepIndex = ref(0)
const saving = ref(false), saveError = ref(''), notice = ref('')
const dialog = ref<HTMLElement>(), heading = ref<HTMLElement>(), errorMessage = ref<HTMLElement>()
const step = computed(() => steps[stepIndex.value]!)
const highlightStyle = ref<Record<string, string>>({ display: 'none' })
const dialogStyle = ref<Record<string, string>>({ visibility: 'hidden' })
const hasTarget = ref(false)
let pendingStatus: SavedStatus = 'DISMISSED'
let interacted = false, disposed = false, frame = 0
let previousFocus: HTMLElement | null = null, previousOverflow = ''
let previousScroll = { x: 0, y: 0 }
let navigationScroll: { element: HTMLElement; left: number }[] = []
let resizeObserver: ResizeObserver | undefined
const controllers = new Set<AbortController>()

async function requestState(status?: SavedStatus) {
  const controller = new AbortController()
  controllers.add(controller)
  const timeout = window.setTimeout(() => controller.abort(), 12000)
  try {
    const value = await api<TutorialState>('/admin/onboarding', {
      signal: controller.signal,
      ...(status ? { method: 'PUT', body: JSON.stringify({ tutorialId, status }) } : {}),
    })
    if (value?.tutorialId !== tutorialId || !['NOT_STARTED', 'DISMISSED', 'COMPLETED'].includes(value?.status)) {
      throw new Error('教程状态暂时无法确认。')
    }
    // Completion is retained by the server when an already completed guide is replayed and dismissed.
    if (status && value.status !== status && !(status === 'DISMISSED' && value.status === 'COMPLETED')) {
      throw new Error('服务器尚未确认本次教程选择。')
    }
    return value
  } finally {
    window.clearTimeout(timeout)
    controllers.delete(controller)
  }
}

async function load() {
  try {
    const value = await requestState()
    if (!disposed && !interacted && value.status === 'NOT_STARTED') show()
  } catch {
    if (!disposed) {
      loadFailed.value = true
      notice.value = '暂时无法读取教程状态。工作台可照常使用，也可点击“使用教程”查看操作方法。'
    }
  } finally {
    if (!disposed) loaded.value = true
  }
}

function targetElement() { return document.querySelector<HTMLElement>(`[data-tour="${step.value.target}"]`) }
function updatePosition() {
  frame = 0
  if (!open.value || !dialog.value) return
  const viewport = window.visualViewport
  const width = viewport?.width || window.innerWidth, height = viewport?.height || window.innerHeight
  const offsetX = viewport?.offsetLeft || 0, offsetY = viewport?.offsetTop || 0
  const target = targetElement(), rect = target?.getBoundingClientRect()
  const visible = !!rect && rect.bottom > offsetY && rect.top < offsetY + height && rect.right > offsetX && rect.left < offsetX + width
  hasTarget.value = visible
  if (visible && rect) {
    const left = Math.max(offsetX + 3, rect.left - 4), top = Math.max(offsetY + 3, rect.top - 4)
    highlightStyle.value = {
      left: `${left}px`, top: `${top}px`,
      width: `${Math.max(0, Math.min(offsetX + width - 3, rect.right + 4) - left)}px`,
      height: `${Math.max(0, Math.min(offsetY + height - 3, rect.bottom + 4) - top)}px`,
    }
  } else highlightStyle.value = { display: 'none' }
  const cardWidth = Math.min(440, width - 28)
  let left = offsetX + (width - cardWidth) / 2, top = offsetY + 18, maxHeight = height - 36
  if (width <= 760) {
    top = visible && rect ? Math.max(offsetY + 14, rect.bottom + 20) : offsetY + 18
    // Keep the highlighted mobile navigation above the scrollable instruction card.
    top = Math.min(top, offsetY + Math.max(18, height - 260))
    maxHeight = offsetY + height - top - 14
  } else {
    const cardHeight = Math.min(dialog.value.getBoundingClientRect().height || 520, maxHeight)
    if (visible && rect) {
      if (rect.right + 22 + cardWidth <= offsetX + width - 18) left = rect.right + 22
      top = Math.max(offsetY + 18, Math.min(rect.top - 8, offsetY + height - cardHeight - 18))
    } else top = offsetY + Math.max(18, (height - cardHeight) / 2)
  }
  dialogStyle.value = { left: `${left}px`, top: `${top}px`, width: `${cardWidth}px`, maxHeight: `${maxHeight}px` }
}
function schedulePosition() {
  if (!frame && open.value) frame = requestAnimationFrame(updatePosition)
}
function revealTarget() {
  const target = targetElement()
  if (!target) { schedulePosition(); return }
  const rect = target.getBoundingClientRect(), nav = target.closest('nav')?.getBoundingClientRect()
  const mobile = window.innerWidth <= 760
  if (rect.top < 12 || rect.bottom > (mobile ? window.innerHeight / 3 : window.innerHeight - 12)
    || rect.left < Math.max(0, nav?.left || 0) || rect.right > Math.min(window.innerWidth, nav?.right || window.innerWidth)) {
    target.scrollIntoView({ block: mobile ? 'start' : 'nearest', inline: 'nearest', behavior: 'instant' })
  }
  schedulePosition()
}
function focusableElements() {
  return Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), [href], input:not(:disabled), [tabindex="0"]') || [])
    .filter(element => element.getClientRects().length > 0)
}
function onKeydown(event: KeyboardEvent) {
  if (!open.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    if (!saving.value) {
      if (saveError.value) closeUnsaved()
      else void save('DISMISSED')
    }
    return
  }
  if (event.key !== 'Tab') return
  const elements = focusableElements(), first = elements[0], last = elements[elements.length - 1]
  if (!first) { event.preventDefault(); heading.value?.focus(); return }
  if (event.shiftKey && (document.activeElement === first || !elements.includes(document.activeElement as HTMLElement))) {
    event.preventDefault(); last?.focus()
  } else if (!event.shiftKey && (document.activeElement === last || !elements.includes(document.activeElement as HTMLElement))) {
    event.preventDefault(); first.focus()
  }
}
function containFocus(event: FocusEvent) {
  if (open.value && dialog.value && !dialog.value.contains(event.target as Node)) heading.value?.focus({ preventScroll: true })
}
function attach() {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  window.addEventListener('resize', revealTarget)
  window.addEventListener('scroll', schedulePosition, true)
  window.visualViewport?.addEventListener('resize', revealTarget)
  window.visualViewport?.addEventListener('scroll', schedulePosition)
  document.addEventListener('keydown', onKeydown, true)
  document.addEventListener('focusin', containFocus)
  resizeObserver = new ResizeObserver(schedulePosition)
  if (dialog.value) resizeObserver.observe(dialog.value)
  const sidebar = document.querySelector('.sidebar')
  if (sidebar) resizeObserver.observe(sidebar)
}
function detach() {
  document.body.style.overflow = previousOverflow
  window.removeEventListener('resize', revealTarget)
  window.removeEventListener('scroll', schedulePosition, true)
  window.visualViewport?.removeEventListener('resize', revealTarget)
  window.visualViewport?.removeEventListener('scroll', schedulePosition)
  document.removeEventListener('keydown', onKeydown, true)
  document.removeEventListener('focusin', containFocus)
  resizeObserver?.disconnect()
  if (frame) cancelAnimationFrame(frame)
  frame = 0
}
function show() {
  if (disposed || open.value) return
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  previousScroll = { x: window.scrollX, y: window.scrollY }
  navigationScroll = Array.from(document.querySelectorAll<HTMLElement>('.sidebar nav')).map(element => ({ element, left: element.scrollLeft }))
  stepIndex.value = 0
  saveError.value = ''
  notice.value = ''
  dialogStyle.value = { visibility: 'hidden' }
  open.value = true
  emit('openChange', true)
  void nextTick(() => {
    if (!open.value || disposed) return
    attach()
    revealTarget()
    heading.value?.focus({ preventScroll: true })
  })
}
function replay() { interacted = true; show() }
async function close() {
  if (!open.value) return
  open.value = false
  detach()
  emit('openChange', false)
  await nextTick()
  if (disposed) return
  navigationScroll.forEach(({ element, left }) => { element.scrollLeft = left })
  window.scrollTo({ left: previousScroll.x, top: previousScroll.y, behavior: 'instant' })
  const focusTarget = previousFocus?.isConnected && previousFocus !== document.body
    ? previousFocus : document.querySelector<HTMLElement>('[data-testid="onboarding-replay"]')
  focusTarget?.focus({ preventScroll: true })
}
async function save(status: SavedStatus) {
  if (saving.value) return
  interacted = true
  pendingStatus = status
  saving.value = true
  saveError.value = ''
  try {
    await requestState(status)
    if (!disposed) { loadFailed.value = false; await close() }
  } catch (error) {
    if (!disposed) {
      saveError.value = `${error instanceof Error ? error.message : '保存失败。'} 本次选择尚未保存，可重试或仅关闭本次教程。`
      await nextTick()
      const content = dialog.value?.querySelector('.onboarding-content')
      if (content) content.scrollTop = content.scrollHeight
      errorMessage.value?.focus({ preventScroll: true })
    }
  } finally { if (!disposed) saving.value = false }
}
function closeUnsaved() {
  interacted = true
  notice.value = '已关闭本次教程，但选择尚未保存；下次进入后台时可能再次显示。工作台可照常使用。'
  void close()
}
function move(delta: number) { if (!saving.value) stepIndex.value = Math.max(0, Math.min(steps.length - 1, stepIndex.value + delta)) }
watch(stepIndex, async () => {
  await nextTick()
  if (!open.value) return
  if (dialog.value) dialog.value.querySelector('.onboarding-content')?.scrollTo({ top: 0 })
  revealTarget()
  heading.value?.focus({ preventScroll: true })
})
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  controllers.forEach(controller => controller.abort())
  if (open.value) detach()
})
defineExpose({ replay })
</script>

<template>
  <div data-testid="onboarding-root" :data-ready="loaded ? 'true' : 'false'" :data-state="!loaded ? 'loading' : loadFailed ? 'error' : 'ready'">
    <span class="onboarding-sr-only" data-testid="onboarding-ready" :data-state="!loaded ? 'loading' : loadFailed ? 'error' : 'ready'">{{ !loaded ? '正在读取教程状态' : loadFailed ? '教程状态暂时无法读取' : '教程状态已读取' }}</span>
    <Teleport to="body">
      <div v-if="notice && !open" class="onboarding-notice" role="status" data-testid="onboarding-notice"><span>{{ notice }}</span><button type="button" @click="notice = ''">知道了</button></div>
      <div v-if="open" class="onboarding-layer" :class="{ 'without-target': !hasTarget }">
        <div class="onboarding-highlight" :style="highlightStyle" data-testid="onboarding-highlight" :data-target="step.target" aria-hidden="true"></div>
        <section ref="dialog" class="onboarding-dialog" :style="dialogStyle" role="dialog" aria-modal="true" aria-labelledby="onboarding-title" aria-describedby="onboarding-description" data-testid="onboarding-dialog" :data-step="stepIndex" :data-step-key="step.key" :aria-busy="saving">
          <header class="onboarding-header">
            <div class="onboarding-heading-line"><span class="onboarding-label"><AppIcon name="help" :size="15" /> 工作台使用教程</span><span data-testid="onboarding-status" :data-step-key="step.key">第 {{ stepIndex + 1 }} / {{ steps.length }} 步</span></div>
            <div class="onboarding-meter" aria-hidden="true"><span v-for="(_, index) in steps" :key="index" :class="{ complete: index <= stepIndex }"></span></div>
          </header>
          <div class="onboarding-content">
            <div class="onboarding-entry"><span class="onboarding-entry-icon"><AppIcon :name="step.icon" :size="23" /></span><div><small>高亮入口位置</small><strong>{{ step.entry }}</strong></div><AppIcon name="arrow" :size="20" /></div>
            <h2 id="onboarding-title" ref="heading" tabindex="-1" data-testid="onboarding-step-title">{{ step.title }}</h2>
            <p id="onboarding-description" class="onboarding-description">{{ step.description }}</p>
            <ol class="onboarding-actions"><li v-for="(action, index) in step.actions" :key="action.label"><span>{{ index + 1 }}</span><div><strong>{{ action.label }}</strong><p>{{ action.detail }}</p></div></li></ol>
            <p class="onboarding-note">{{ step.note }}</p>
            <p v-if="saveError" ref="errorMessage" tabindex="-1" class="onboarding-error" role="alert" data-testid="onboarding-save-error">{{ saveError }}</p>
          </div>
          <footer class="onboarding-footer">
            <template v-if="saveError">
              <button type="button" class="secondary" @click="closeUnsaved" data-testid="onboarding-close-unsaved">仅关闭本次教程</button>
              <button type="button" class="primary" @click="save(pendingStatus)" data-testid="onboarding-retry">重试保存</button>
            </template>
            <template v-else>
              <button type="button" class="onboarding-skip" :disabled="saving" @click="save('DISMISSED')" data-testid="onboarding-skip">稍后再看</button>
              <div class="onboarding-navigation"><button v-if="stepIndex > 0" type="button" class="secondary" :disabled="saving" @click="move(-1)" data-testid="onboarding-back">上一步</button><button v-if="stepIndex < steps.length - 1" type="button" class="primary" :disabled="saving" @click="move(1)" data-testid="onboarding-next">下一步 <AppIcon name="arrow" :size="15" /></button><button v-else type="button" class="primary" :disabled="saving" @click="save('COMPLETED')" data-testid="onboarding-finish">{{ saving ? '正在保存…' : '完成教程' }}</button></div>
            </template>
          </footer>
          <p class="onboarding-safe-caption">这里只说明点击方法，完成后回到原页面操作</p>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.onboarding-sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
.onboarding-layer{position:fixed;inset:0;z-index:1000;isolation:isolate;overflow:hidden;overscroll-behavior:contain}
.onboarding-layer.without-target{background:rgba(9,25,47,.62)}
.onboarding-highlight{position:fixed;box-sizing:border-box;border:2px solid #a9ceff;border-radius:9px;box-shadow:0 0 0 4px #5298ff55,0 0 0 9999px rgba(9,25,47,.62);pointer-events:none}
.onboarding-dialog{position:fixed;display:flex;flex-direction:column;background:#fff;border:1px solid #dce8f7;border-radius:14px;box-shadow:0 24px 80px #03132a55;color:#2c405d;overflow:hidden;box-sizing:border-box}
.onboarding-header{padding:21px 25px 0;flex-shrink:0}
.onboarding-heading-line{display:flex;justify-content:space-between;align-items:center;gap:12px;font-size:11px;color:#8492a7}
.onboarding-label{display:flex;align-items:center;gap:7px;color:#3e6bab;font-weight:550}
.onboarding-meter{display:flex;gap:5px;margin-top:15px}
.onboarding-meter span{flex:1;height:3px;background:#eaf0f8;border-radius:3px}
.onboarding-meter span.complete{background:#477de0}
.onboarding-content{padding:22px 25px 0;overflow-y:auto;overscroll-behavior:contain;min-height:0}
.onboarding-entry{display:flex;gap:11px;align-items:center;margin-bottom:20px}
.onboarding-entry-icon{width:43px;height:43px;background:#edf4ff;border:1px solid #dce9fd;border-radius:10px;display:grid;place-items:center;color:#3974d6}
.onboarding-entry small{display:block;font-size:10px;color:#8495ad;margin-bottom:2px}
.onboarding-entry strong{display:block;font-size:13px;font-weight:600;color:#376097}
.onboarding-entry>.app-icon{margin-left:auto;color:#83a5d5;transform:rotate(180deg)}
.onboarding-dialog h2{font-size:21px;line-height:1.5;letter-spacing:-.25px;color:#193b65;margin:0}
.onboarding-dialog h2:focus{outline:none}
.onboarding-description{font-size:12px;line-height:1.8;color:#78889e;margin:9px 0 20px}
.onboarding-actions{list-style:none;padding:0;margin:0;display:flex;flex-direction:column;gap:17px}
.onboarding-actions li{display:flex;gap:10px;align-items:flex-start}
.onboarding-actions li>span{display:grid;place-items:center;flex-shrink:0;width:20px;height:20px;border-radius:50%;background:#eef4fd;font-size:10px;font-weight:600;color:#4e7cbc;margin-top:1px}
.onboarding-actions strong{font-size:12px;font-weight:600;line-height:1.7;color:#304e75}
.onboarding-actions p{font-size:11px;line-height:1.85;color:#7b8ba1;margin:4px 0 0}
.onboarding-note{padding:12px 13px;background:#f4f7fc;border-left:2px solid #b5cceb;border-radius:0 5px 5px 0;font-size:10px;line-height:1.9;color:#7085a2;margin:21px 0 0}
.onboarding-error{padding:11px 13px;border:1px solid #f2c6c2;border-radius:6px;background:#fff5f4;color:#ac493f;font-size:12px;line-height:1.8;margin:14px 0 0}
.onboarding-footer{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:21px 25px 0;flex-shrink:0}
.onboarding-navigation{display:flex;align-items:center;gap:8px}
.onboarding-footer .primary,.onboarding-footer .secondary{min-height:38px;padding:8px 13px;font-size:12px}
.onboarding-skip{background:none;color:#8290a4;font-size:11px;padding:8px 0;white-space:nowrap}
.onboarding-skip:hover{color:#315e9a}
.onboarding-safe-caption{margin:12px 25px 17px;text-align:right;font-size:9px;color:#9aa8ba;flex-shrink:0}
.onboarding-notice{position:fixed;z-index:1001;right:24px;bottom:22px;width:min(430px,calc(100vw - 32px));box-sizing:border-box;display:flex;align-items:center;gap:16px;padding:15px 18px;background:#fff;border:1px solid #dce6f4;border-radius:9px;box-shadow:0 6px 25px #2441661c;color:#6c809d;font-size:12px;line-height:1.8}
.onboarding-notice button{white-space:nowrap;color:#386cb6;background:transparent;font-size:11px;padding:4px}
@media(max-width:760px){.onboarding-header{padding:17px 19px 0}.onboarding-content{padding:17px 19px 0}.onboarding-entry{margin-bottom:15px}.onboarding-entry>.app-icon{transform:rotate(-90deg)}.onboarding-dialog h2{font-size:19px}.onboarding-description{margin-bottom:17px}.onboarding-actions{gap:14px}.onboarding-footer{padding:17px 19px 0}.onboarding-footer .primary,.onboarding-footer .secondary{padding:8px 11px}.onboarding-safe-caption{margin:11px 19px 14px;font-size:8px}.onboarding-notice{right:16px;bottom:16px}}
@media print{.onboarding-layer,.onboarding-notice{display:none!important}}
</style>
