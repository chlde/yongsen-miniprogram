const api = require('../../lib/api');
const { enrichAssignment, dateLabel } = require('../../lib/view');
Page({
  data: { task: null, assignments: [], notifications: [], busy: false, error: '', completedCount: 0, readyCount: 0 },
  onLoad(query) { this.taskId = query.id; },
  onShow() { this.load(); },
  onHide() { this.stopWatch(); },
  onUnload() { this.stopWatch(); },
  stopWatch() { if (this.watcher) this.watcher.close(); this.watcher = null; clearTimeout(this.refreshTimer); },
  async load() {
    try {
      const me = await api.request('/me'); if (me.role !== 'ADMIN') return wx.reLaunch({ url: '/pages/login/index' });
      const [tasks, assignments, notifications] = await Promise.all([api.list('tasks', { _id: this.taskId }), api.list('assignments', { taskId: this.taskId }), api.list('notifications', { taskId: this.taskId })]);
      const task = tasks.items[0]; if (!task) throw new Error('任务不存在');
      task.dueLabel = dateLabel(task.dueAt);
      this.setData({ task, assignments: assignments.items.map(enrichAssignment), notifications: notifications.items,
        completedCount: assignments.items.filter(item => item.status === 'COMPLETED').length, readyCount: assignments.items.filter(item => item.status === 'READY_TO_CONFIRM').length, error: '' });
      if (!this.watcher) {
        let initial = true;
        this.watcher = api.watch('assignments', { taskId: this.taskId }, () => {
          if (initial) { initial = false; return; } clearTimeout(this.refreshTimer); this.refreshTimer = setTimeout(() => this.load(), 600);
        }, () => this.setData({ syncError: '实时连接断开，请点击刷新' }));
      }
    } catch (error) { this.setData({ error: error.message }); }
  },
  async action(action) {
    if (this.data.busy) return; this.setData({ busy: true, error: '' });
    try { await api.call(action, { taskId: this.taskId }); await this.load(); }
    catch (error) { this.setData({ error: error.message }); } finally { this.setData({ busy: false }); }
  },
  publish() { this.action('publishTask'); },
  remind() { this.action('remindTask'); },
  cancel() { wx.showModal({ title: '取消培训任务', content: '取消后员工将不能继续学习或确认，已有记录保留。', success: result => { if (result.confirm) this.action('cancelTask'); } }); },
  async exportRecords() {
    try {
      const rows = [['员工', '任务', '状态', '截止时间', '完成时间'], ...this.data.assignments.map(row => [row.personName, row.title, row.stateLabel, row.dueLabel, row.completedAt || row.confirmedAt ? dateLabel(row.completedAt || row.confirmedAt) : ''])];
      const quote = value => `"${String(value || '').replace(/^[=+@\\-]/, "'$&").replace(/"/g, '""')}"`;
      const csv = '\ufeff' + rows.map(row => row.map(quote).join(',')).join('\r\n');
      const filePath = `${wx.env.USER_DATA_PATH}/training-${this.taskId}.csv`;
      await new Promise((resolve, reject) => wx.getFileSystemManager().writeFile({ filePath, data: csv, encoding: 'utf8', success: resolve, fail: reject }));
      if (wx.shareFileMessage) wx.shareFileMessage({ filePath, fileName: '培训记录.csv' });
      else wx.showToast({ title: '文件已保存，请在新版微信中导出', icon: 'none' });
    } catch (error) { this.setData({ error: error.message || error.errMsg }); }
  }
});
