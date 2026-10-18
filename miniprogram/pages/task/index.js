const api = require('../../lib/api');
const { enrichAssignment, enrichMaterial, toastError } = require('../../lib/view');
Page({
  data: { loading: true, task: null, error: '', accepted: false, confirming: false, subscriptionReady: false, subscribing: false, mock: false },
  onLoad(query) { this.assignmentId = query.id || query.assignmentId; this.notificationId = query.notificationId || query.notice; },
  onShow() { this.load(); },
  async load() {
    if (!getApp().globalData.token) return this.requestLogin();
    this.setData({ loading: true, error: '', accepted: false });
    try {
      const task = await api.request(`/me/assignments/${this.assignmentId}`);
      task.materials = (task.materials || []).map(enrichMaterial);
      this.setData({ task: enrichAssignment(task), mock: getApp().globalData.providerMode === 'mock' });
      if (!this.entered) { await api.post(`/me/assignments/${this.assignmentId}/entered`, this.notificationId ? { notificationId: this.notificationId } : {}); this.entered = true; }
      this.prepareSubscription();
    } catch (error) { if (!error.authExpired) this.setData({ error: error.message }); }
    finally { this.setData({ loading: false }); }
  },
  requestLogin() {
    getApp().globalData.pendingTaskRoute = `/pages/task/index?assignmentId=${encodeURIComponent(this.assignmentId)}${this.notificationId ? '&notificationId=' + encodeURIComponent(this.notificationId) : ''}`;
    wx.reLaunch({ url: '/pages/login/index' });
  },
  async prepareSubscription() {
    this.subscription = null; this.subscriptionError = '';
    this.setData({ subscriptionReady: false });
    try {
      const settings = await api.request('/me/notification-settings');
      this.providerMode = settings.providerMode;
      this.setData({ mock: settings.providerMode === 'mock' });
      if (settings.configured === false || !settings.templateId) { this.subscriptionError = '管理员尚未配置微信通知模板'; return; }
      this.subscription = await api.post('/me/subscriptions');
      this.setData({ subscriptionReady: true });
    } catch (error) { this.subscriptionError = error.message; }
  },
  subscribe() {
    if (this.data.subscribing) return;
    if (!this.subscription || !this.data.subscriptionReady) return wx.showToast({ title: this.subscriptionError || '通知配置正在加载，请稍后重试', icon: 'none' });
    this.setData({ subscribing: true });
    const request = this.subscription;
    const report = async result => {
      try {
        await api.post(`/me/subscriptions/${request.id || request.requestId}/result`, { nonce: request.nonce, result });
        wx.showToast({ title: result === 'accept' ? (this.providerMode === 'mock' ? '已记录本地模拟订阅' : '已记录本次订阅') : '未订阅，仍可继续学习', icon: 'none' });
      } catch (error) { toastError(error); }
      finally { this.setData({ subscribing: false }); this.prepareSubscription(); }
    };
    if (this.providerMode === 'mock') {
      if (!getApp().globalData.localMock || !getApp().canUseLocalMock()) { this.setData({ subscribing: false }); return wx.showToast({ title: '模拟通知仅限显式本机测试', icon: 'none' }); }
      report('accept'); return;
    }
    // This call is directly triggered by the user's tap. The nonce is prepared before the tap.
    wx.requestSubscribeMessage({ tmplIds: [request.templateId],
      success: result => report(result[request.templateId] || 'reject'),
      fail: error => { this.setData({ subscribing: false }); wx.showToast({ title: error.errMsg || '微信订阅未完成', icon: 'none' }); this.prepareSubscription(); }
    });
  },
  openMaterial(e) {
    const id = e.currentTarget.dataset.id;
    if (!this.data.task || ['CANCELED', 'CANCELLED', 'CLOSED'].includes(this.data.task.taskStatus)) return wx.showToast({ title: '任务已停止学习', icon: 'none' });
    wx.navigateTo({ url: `/pages/learn/index?assignmentId=${encodeURIComponent(this.assignmentId)}&taskMaterialId=${encodeURIComponent(id)}` });
  },
  onAccept(e) { this.setData({ accepted: e.detail.value.includes('accepted') }); },
  async confirm() {
    if (!this.data.accepted || this.data.confirming) return;
    this.setData({ confirming: true });
    try {
      await api.post(`/me/assignments/${this.assignmentId}/confirm`, { accepted: true, progressDigest: this.data.task.progressDigest, confirmationTextVersion: this.data.task.confirmationTextVersion || 'mvp-1' }, this.confirmKey || (this.confirmKey = api.requestId('confirm')));
      wx.showToast({ title: '已保存本人确认', icon: 'success' }); await this.load();
    } catch (error) { this.confirmKey = null; toastError(error); if (error.code === 'PROGRESS_CHANGED') this.load(); }
    finally { this.setData({ confirming: false }); }
  }
});
