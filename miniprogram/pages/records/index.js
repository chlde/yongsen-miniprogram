const api = require('../../lib/api');
const { enrichAssignment, toastError } = require('../../lib/view');
Page({
  data: { person: null, items: [], loading: true, error: '' },
  onShow() { this.load(); },
  async load() {
    if (!getApp().globalData.identity) return wx.reLaunch({ url: '/pages/login/index' });
    this.setData({ loading: true, error: '' });
    try {
      const me = await api.request('/me');
      if (me.bindingStatus !== 'ACTIVE' && me.role !== 'ADMIN') return wx.reLaunch({ url: '/pages/login/index' });
      const result = me.bindingStatus === 'ACTIVE' ? await api.request('/me/assignments') : { items: [] };
      this.setData({ person: me.person, items: (result.items || []).filter(item => item.status === 'COMPLETED').map(enrichAssignment) });
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ loading: false }); }
  },
  openRecord(e) { wx.navigateTo({ url: `/pages/task/index?id=${encodeURIComponent(e.currentTarget.dataset.id)}` }); },
  logout() { getApp().clearLogin(); wx.reLaunch({ url: '/pages/login/index' }); }
});
