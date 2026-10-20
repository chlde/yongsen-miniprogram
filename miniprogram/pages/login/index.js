const api = require('../../lib/api');
Page({
  data: { busy: false, loggedIn: false, bindingStatus: 'UNBOUND', inviteCode: '', person: null, error: '', roleCode: '' },
  onShow() { if (getApp().globalData.identity) this.refreshIdentity(); },
  async refreshIdentity() {
    try {
      const me = await api.request('/me');
      this.setData({ bindingStatus: me.bindingStatus, person: me.person, role: me.role, loggedIn: true });
      if (me.bindingStatus === 'ACTIVE' || me.role === 'ADMIN') {
        const pending = getApp().globalData.pendingTaskRoute; getApp().globalData.pendingTaskRoute = null;
        if (pending && me.bindingStatus === 'ACTIVE') wx.reLaunch({ url: pending });
        else wx.switchTab({ url: '/pages/tasks/index' });
      }
    } catch (error) { this.setData({ error: error.message }); }
  },
  async login() { if (this.data.busy) return; this.setData({ busy: true, error: '' }); try { await this.refreshIdentity(); } finally { this.setData({ busy: false }); } },
  onInviteInput(e) { this.setData({ inviteCode: e.detail.value.trim() }); },
  async bindInvitation() {
    if (this.data.busy || !this.data.inviteCode) return; this.setData({ busy: true, error: '' });
    try { await api.call('bind', { inviteCode: this.data.inviteCode }); await this.refreshIdentity(); }
    catch (error) { this.setData({ error: error.message }); } finally { this.setData({ busy: false }); }
  },
  async showRoleCode() { try { const result = await api.call('requestRoleCode'); this.setData({ roleCode: result.code }); } catch (error) { this.setData({ error: error.message }); } },
  logout() { getApp().clearLogin(); this.setData({ loggedIn: false, bindingStatus: 'UNBOUND', person: null, roleCode: '' }); }
});
