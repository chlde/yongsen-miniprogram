const api = require('../../lib/api');
const { enrichAssignment, toastError } = require('../../lib/view');
Page({
  data: { loading: true, items: [], activeItems: [], person: null, completedCount: 0, error: '' },
  onShow() { this.load(); },
  async load() {
    if (!getApp().globalData.identity) return wx.reLaunch({ url: '/pages/login/index' });
    this.setData({ loading: true, error: '' });
    try {
      const me = await api.request('/me');
      if (me.bindingStatus !== 'ACTIVE' && me.role !== 'ADMIN') return wx.reLaunch({ url: '/pages/login/index' });
      getApp().globalData.person = me.person; getApp().globalData.providerMode = me.providerMode;
      const result = me.bindingStatus === 'ACTIVE' ? await api.request('/me/assignments') : { items: [] };
      const items = (result.items || []).map(enrichAssignment);
      this.setData({ items, activeItems: items.filter(item => item.status !== 'COMPLETED'), person: me.person, role: me.role, completedCount: items.filter(item => item.status === 'COMPLETED').length });
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ loading: false }); wx.stopPullDownRefresh(); }
  },
  onPullDownRefresh() { this.load(); },
  openTask(e) { wx.navigateTo({ url: `/pages/task/index?id=${encodeURIComponent(e.currentTarget.dataset.id)}` }); },
  openRecords() { wx.switchTab({ url: '/pages/records/index' }); },
  openAdmin() { wx.navigateTo({ url: '/admin/home/index' }); }
});
