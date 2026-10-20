const api = require('../../lib/api');
const { dateLabel, enrichAssignment } = require('../../lib/view');
const labels = { DRAFT: '草稿', PUBLISHING: '发布中', ACTIVE: '进行中', CANCELED: '已取消', UPLOADING: '待上传', PROCESSING: '处理中', READY: '可使用', REJECTED: '处理失败', PENDING: '待处理', ACCEPTED: '微信已受理', UNKNOWN: '发送结果待核对', SKIPPED: '已跳过' };
Page({
  data: { section: 'tasks', busy: false, error: '', tasks: [], materials: [], people: [], bindings: [], roles: [], jobs: [], selectedPeople: [], selectedMaterials: [], creating: false, form: {}, invite: '', roleCode: '', roleSources: [], templateFields: '{"thing1":"title","time20":"dueAt"}' },
  onShow() { this.load(); },
  onHide() { this.stopWatch(); },
  onUnload() { this.stopWatch(); },
  stopWatch() { if (this.watcher) this.watcher.close(); this.watcher = null; clearTimeout(this.refreshTimer); },
  async load() {
    try {
      const me = await api.request('/me');
      if (me.role !== 'ADMIN') { wx.reLaunch({ url: '/pages/login/index' }); return; }
      const section = this.data.section;
      const tables = { tasks: ['tasks', 'jobs'], materials: ['materials'], people: ['people', 'bindings'], roles: ['roles'], settings: [] }[section];
      for (const table of tables) {
        let result;
        if (table === 'roles') result = await api.call('adminList', { table });
        else result = await api.list(table, {});
        this.setData({ [table]: result.items.map(row => Object.assign({}, row, { stateLabel: labels[row.status || row.state] || row.status || row.state, dateLabel: dateLabel(row.createdAt), roleLabel: row.role === 'ADMIN' ? '管理员' : '普通用户', sourceLabel: (row.sources || []).map(s => s === 'PLATFORM_ADMIN' ? '平台管理员' : '项目成员').join('、') })).reverse() });
      }
      this.setData({ error: '' });
      this.stopWatch();
      const watched = tables.find(table => table !== 'roles');
      if (watched) this.watcher = api.watch(watched, {}, () => {
        if (this.watchReady) { clearTimeout(this.refreshTimer); this.refreshTimer = setTimeout(() => this.load(), 1000); }
        this.watchReady = true;
      }, () => { this.setData({ syncLabel: '实时连接暂不可用，点击刷新可更新' }); });
      this.watchReady = false;
    } catch (error) { this.setData({ error: error.message }); }
  },
  changeSection(e) { this.stopWatch(); this.setData({ section: e.currentTarget.dataset.section, creating: false, form: {}, error: '', invite: '' }); this.load(); },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.field}`]: e.detail.value }); },
  async action(fn) {
    if (this.data.busy) return;
    this.setData({ busy: true, error: '' });
    try { await fn(); await this.load(); } catch (error) { this.setData({ error: error.message || error.errMsg }); }
    finally { this.setData({ busy: false }); }
  },
  newPerson() { this.setData({ creating: true, form: {} }); },
  savePerson() { this.action(async () => { await api.call('savePerson', this.data.form); this.setData({ creating: false, form: {} }); }); },
  invite(e) { this.action(async () => { const result = await api.call('invitePerson', { personId: e.currentTarget.dataset.id }); this.setData({ invite: result.inviteCode }); }); },
  copyInvite() { wx.setClipboardData({ data: this.data.invite }); },
  decide(e) { this.action(async () => { await api.call('decideBinding', { id: e.currentTarget.dataset.id, decision: e.currentTarget.dataset.decision }); }); },
  uploadMaterial() {
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf', 'mp4'], success: result => this.action(async () => {
      const file = result.tempFiles[0];
      await api.upload(file, file.name.replace(/\.(pdf|mp4)$/i, ''));
      wx.showToast({ title: '已上传，正在处理', icon: 'none' });
    }), fail: error => { if (!/cancel/.test(error.errMsg)) this.setData({ error: error.errMsg }); } });
  },
  async newTask() {
    try {
      const [people, materials] = await Promise.all([api.list('people', { status: 'ACTIVE' }), api.list('materials', { status: 'READY' })]);
      this.setData({ creating: true, form: { dueDate: new Date(Date.now() + 7 * 86400000 + 8 * 3600000).toISOString().slice(0, 10) }, people: people.items.filter(person => person.openid), materials: materials.items, selectedPeople: [], selectedMaterials: [] });
    } catch (error) { this.setData({ error: error.message }); }
  },
  selectPeople(e) { this.setData({ selectedPeople: e.detail.value }); },
  selectMaterials(e) { this.setData({ selectedMaterials: e.detail.value }); },
  dateChange(e) { this.setData({ 'form.dueDate': e.detail.value }); },
  createTask() {
    this.action(async () => {
      if (!this.data.selectedPeople.length || !this.data.selectedMaterials.length) throw new Error('请选择员工和教材');
      const result = await api.call('createTask', { title: this.data.form.title, description: this.data.form.description, dueAt: this.data.form.dueDate ? `${this.data.form.dueDate}T23:59:59+08:00` : null,
        personIds: this.data.selectedPeople, materials: this.data.selectedMaterials.map(materialId => ({ materialId, rule: { minPageVisibleMs: 30000 } })) }, this.createKey || (this.createKey = api.requestId('create-task')));
      this.createKey = null; this.setData({ creating: false, form: {} }); wx.navigateTo({ url: `/admin/task/index?id=${result.id}` });
    });
  },
  openTask(e) { wx.navigateTo({ url: `/admin/task/index?id=${e.currentTarget.dataset.id}` }); },
  cancelCreate() { this.createKey = null; this.setData({ creating: false }); this.load(); },
  runJobs() { this.action(async () => { await api.call('runJobs'); }); },
  retryJob(e) { this.action(async () => { await api.call('retryJob', { id: e.currentTarget.dataset.id }); }); },
  roleSourceChange(e) { this.setData({ roleSources: e.detail.value }); },
  setRole() { this.action(async () => { await api.call('setRole', { code: this.data.form.code, platformName: this.data.form.platformName, sources: this.data.roleSources, evidence: this.data.form.evidence }); this.setData({ form: {}, roleSources: [] }); }); },
  revokeRole(e) { this.action(async () => { await api.call('revokeRole', { accountId: e.currentTarget.dataset.id, source: e.currentTarget.dataset.source }); }); },
  async myCode() { try { const result = await api.call('requestRoleCode'); this.setData({ roleCode: result.code }); } catch (error) { this.setData({ error: error.message }); } },
  fieldsInput(e) { this.setData({ templateFields: e.detail.value }); },
  saveSettings() { this.action(async () => {
    let fields; try { fields = JSON.parse(this.data.templateFields); } catch (_) { throw new Error('模板字段格式不正确'); }
    await api.call('saveNotificationSettings', { templateId: this.data.form.templateId, fields }); wx.showToast({ title: '已保存' });
  }); }
});
