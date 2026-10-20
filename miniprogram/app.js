const api = require('./lib/api');
App({
  globalData: { identity: null, person: null, bindingStatus: 'UNBOUND', appVisible: true },
  onLaunch() { try { api.initialize(); } catch (error) { this.globalData.loginMessage = error.message; } },
  onShow() { this.globalData.appVisible = true; },
  onHide() {
    this.globalData.appVisible = false;
    const pages = getCurrentPages(), current = pages[pages.length - 1];
    if (current && current.pauseForBackground) current.pauseForBackground();
  },
  saveLogin(identity) {
    this.globalData.identity = identity; this.globalData.person = identity.person;
    this.globalData.bindingStatus = identity.bindingStatus; this.globalData.providerMode = 'wechat-cloud';
  },
  clearLogin() { this.globalData.identity = null; this.globalData.person = null; this.globalData.bindingStatus = 'UNBOUND'; },
  handleAuthExpired() { this.clearLogin(); wx.reLaunch({ url: '/pages/login/index' }); }
});
