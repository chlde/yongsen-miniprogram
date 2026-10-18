const config = require('./lib/config');
const { localMockAllowed, authContext } = require('./lib/environment');
const api = require('./lib/api');
App({
  globalData: { token: '', person: null, bindingStatus: 'UNBOUND', providerMode: '', localMock: false, appVisible: true },
  onLaunch() {
    if (wx.getStorageSync('yongsen.authContext') === authContext(config)) {
      this.globalData.token = wx.getStorageSync('yongsen.accessToken') || '';
    } else this.clearLogin();
    this.globalData.localMock = Boolean(wx.getStorageSync('yongsen.localMock')) && this.canUseLocalMock();
    if (!this.canUseLocalMock()) wx.removeStorageSync('yongsen.localMock');
    try { api.initialize(); } catch (error) { this.globalData.loginMessage = error.message; }
  },
  onShow() { this.globalData.appVisible = true; },
  onHide() {
    this.globalData.appVisible = false;
    const pages = getCurrentPages();
    const current = pages[pages.length - 1];
    if (current && typeof current.pauseForBackground === 'function') current.pauseForBackground();
  },
  canUseLocalMock() {
    try {
      const platform = wx.getDeviceInfo ? wx.getDeviceInfo().platform : wx.getSystemInfoSync().platform;
      return localMockAllowed(config.apiBase, platform, wx.getAccountInfoSync().miniProgram.envVersion, config.transport);
    } catch (_) { return false; }
  },
  saveLogin(result) {
    this.globalData.token = result.accessToken;
    this.globalData.person = result.person;
    this.globalData.bindingStatus = result.bindingStatus;
    wx.setStorageSync('yongsen.accessToken', result.accessToken);
    wx.setStorageSync('yongsen.authContext', authContext(config));
  },
  clearLogin() {
    this.globalData.token = ''; this.globalData.person = null; this.globalData.localMock = false;
    this.globalData.bindingStatus = 'UNBOUND'; this.globalData.providerMode = '';
    wx.removeStorageSync('yongsen.accessToken'); wx.removeStorageSync('yongsen.localMock'); wx.removeStorageSync('yongsen.authContext');
  },
  handleAuthExpired(expectedToken) {
    if (!expectedToken || this.globalData.token !== expectedToken) return;
    const pages = getCurrentPages(), current = pages[pages.length - 1];
    this.clearLogin();
    this.globalData.loginMessage = '登录已过期，请重新登录，已保存的学习进度会保留';
    if (current && typeof current.onLoginExpired === 'function') current.onLoginExpired();
    if (current && current.assignmentId) {
      this.globalData.pendingTaskRoute = `/pages/task/index?assignmentId=${encodeURIComponent(current.assignmentId)}${current.notificationId ? '&notificationId=' + encodeURIComponent(current.notificationId) : ''}`;
    }
    if (current && current.route === 'pages/login/index') {
      current.setData({ loggedIn: false, bindingStatus: 'UNBOUND', person: null, mockEnabled: false, error: this.globalData.loginMessage });
      return;
    }
    if (!this._authRedirecting) {
      this._authRedirecting = true;
      wx.reLaunch({ url: '/pages/login/index', fail: () => { this._authRedirecting = false; } });
    }
  }
});
