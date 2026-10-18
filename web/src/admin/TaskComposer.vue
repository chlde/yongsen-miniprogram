<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAll, post } from '../services/api'
import { useFeedback } from '../composables/useFeedback'
import type { Material, Person } from '../types'
import NoticeBox from '../components/NoticeBox.vue'

const props = defineProps<{ initialTraining?: { title: string; description: string } }>()
const emit = defineEmits<{ created: [id: string]; cancel: [] }>()
const people = ref<Person[]>([])
const materials = ref<Material[]>([])
const personIds = ref<string[]>([])
const materialIds = ref<string[]>([])
const title = ref(props.initialTraining?.title || '')
const description = ref(props.initialTraining?.description || '')
const dueAt = ref('')
const pageSeconds = ref(30)
const { busy, error, run } = useFeedback()
const usable = computed(() => materials.value.filter(material => material.status === 'READY' && material.compatibilityChecked === true))
const includesDocument = computed(() => materialIds.value.some(id => materials.value.find(material => material.id === id)?.type === 'PDF'))
onMounted(() => run(async () => {
  const [personList, materialList] = await Promise.all([getAll<Person>('/admin/people'), getAll<Material>('/admin/materials')])
  people.value = personList.filter(person => person.status === 'ACTIVE')
  materials.value = materialList
}))
async function publish() {
  if (!personIds.value.length || !materialIds.value.length) { error.value = '请选择参训员工和培训材料。'; return }
  await run(async () => {
    const task = await post('/admin/tasks', {
      title: title.value, description: description.value,
      dueAt: dueAt.value ? new Date(dueAt.value).toISOString() : null,
      personIds: personIds.value,
      materials: materialIds.value.map(id => ({ materialId: id, rule: materials.value.find(material => material.id === id)?.type === 'PDF' ? { minPageVisibleMs: pageSeconds.value * 1000 } : { playbackRate: 1 } })),
    })
    await post(`/admin/tasks/${task.id}/publish`)
    emit('created', task.id)
  })
}
</script>
<template>
  <section class="panel">
    <div class="section-heading"><div><p class="eyebrow">CREATE TRAINING</p><h2>安排一项新培训</h2><p class="muted small">明确学习目标，选择培训材料和参训员工后即可发布。</p></div><button class="link" @click="$emit('cancel')">返回列表</button></div>
    <NoticeBox :text="error" kind="error" />
    <NoticeBox v-if="initialTraining" text="已预填培训名称与目标。请根据本次安排修改内容，并自行选择材料与参训人员。" kind="info" />
    <form class="form-stack" @submit.prevent="publish">
      <section class="form-stack">
        <div class="section-heading"><h3>01 · 培训安排</h3><span class="muted small">让员工清楚学什么、何时完成</span></div>
        <div class="form-row"><label>培训名称<input v-model="title" required data-testid="task-title" placeholder="例如：班组安全生产专题培训" /></label><label>完成截止时间（可选）<input v-model="dueAt" type="datetime-local" data-testid="task-due-at" /></label></div>
        <label>培训目标与要求<textarea v-model="description" rows="3" data-testid="task-description" placeholder="说明本次培训的适用岗位、学习重点与完成要求"></textarea></label>
      </section>
      <div class="two-columns even">
        <section>
          <div class="section-heading"><h3>02 · 选择培训材料</h3><span class="counter">已选 {{ materialIds.length }} 份</span></div>
          <p class="small muted">可从资源库选择视频与文档，选中内容均为本次必学材料。</p>
          <div v-if="!usable.length" class="empty compact">暂无可用材料。请先到培训资源上传文件并完成预览确认。</div>
          <div class="selection-list"><label v-for="material in usable" :key="material.id" class="choice-row"><input type="checkbox" v-model="materialIds" :value="material.id" :data-testid="`select-material-${material.title}`" /><span><strong>{{ material.title }}</strong><small>{{ material.type === 'PDF' ? `学习文档 · ${material.pageCount} 页` : '培训视频 · 完整观看' }}</small></span></label></div>
          <label v-if="includesDocument" class="compact-field">每页文档最低阅读时间（秒）<input v-model.number="pageSeconds" type="number" min="1" max="3600" required data-testid="task-page-seconds" /><small>结合内容难度设定，不作为法定培训学时。视频按正常 1 倍速观看。</small></label>
        </section>
        <section>
          <div class="section-heading"><h3>03 · 选择参训员工</h3><span class="counter">已选 {{ personIds.length }} 人</span></div>
          <div class="section-heading"><p class="small muted">按本次培训对象选人，员工在微信中领取自己的任务。</p><button type="button" class="link" @click="personIds = personIds.length === people.length ? [] : people.map(person => person.id)">{{ personIds.length === people.length && people.length ? '清空选择' : '选择全部' }}</button></div>
          <div v-if="!people.length" class="empty compact">暂无有效员工。请先到员工管理完善参训名单。</div>
          <div class="selection-list"><label v-for="person in people" :key="person.id" class="choice-row"><input type="checkbox" v-model="personIds" :value="person.id" :data-testid="`select-person-${person.personNo}`" /><span><strong>{{ person.name }}</strong><small>{{ person.personNo }} · {{ person.unit || '所属单位未填写' }}{{ person.project ? ` · ${person.project}` : '' }}</small></span></label></div>
        </section>
      </div>
      <div class="summary-strip"><span>本次安排 <strong>{{ personIds.length }}</strong> 位员工学习 <strong>{{ materialIds.length }}</strong> 份材料</span><span class="small muted">发布后固定本次材料、人员和要求，请核对后提交。</span></div>
      <p class="muted small">微信提醒需要员工完成身份关联和订阅授权；尚未具备条件的人员会等待条件满足后发送。模板需要截止时间时，请在上方填写。</p>
      <div class="actions"><button class="primary" :disabled="busy || !usable.length" data-testid="task-publish">{{ busy ? '正在发布培训…' : '发布培训任务' }}</button><button type="button" class="secondary" @click="$emit('cancel')" :disabled="busy">暂不发布</button></div>
    </form>
  </section>
</template>
