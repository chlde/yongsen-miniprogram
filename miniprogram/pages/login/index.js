const api = require('../../lib/api');
const { toastError } = require('../../lib/view');
Page({
  data: { busy: false, bindingStatus: 'UNBOUND', inviteCode: '', mockName: 'alice', mockEnabled: false, canMock: false, person: null, error: '' },
  onLoad() { this.setData({ canMock: getApp().canUseLocalMock(), mockEnabled: getApp().globalData.localMock }); },
  onShow() {
    const app = getApp(); app._authRedirecting = false;
    if (app.globalData.loginMessage) { this.setData({ error: app.globalData.loginMessage }); app.globalData.loginMessage = ''; }
    if (app.globalData.token) this.refreshIdentity();
  },
  async refreshIdentity() {
    try {
      const me = await api.request('/me');
      getApp().globalData.bindingStatus = me.bindingStatus;
      getApp().globalData.person = me.person;
      getApp().globalData.providerMode = me.providerMode;
      this.setData({ bindingStatus: me.bindingStatus, person: me.person, loggedIn: true });
      if (me.bindingStatus === 'ACTIVE') {
        const pending = getApp().globalData.pendingTaskRoute;
        getApp().globalData.pendingTaskRoute = null;
        if (pending) wx.reLaunch({ url: pending });
        else wx.switchTab({ url: '/pages/tasks/index' });
      }
    } catch (error) { if (error.statusCode === 401) this.setData({ loggedIn: false }); else toastError(error); }
  },
  onInviteInput(e) { this.setData({ inviteCode: e.detail.value.trim() }); },
  onMockNameInput(e) { this.setData({ mockName: e.detail.value }); },
  onMockSwitch(e) {
    const enabled = Boolean(e.detail.value) && getApp().canUseLocalMock();
    getApp().globalData.localMock = enabled;
    wx.setStorageSync('yongsen.localMock', enabled);
    this.setData({ mockEnabled: enabled });
  },
  async login() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: '' });
    try {
      let code;
      if (this.data.mockEnabled) {
        if (!getApp().canUseLocalMock()) throw new Error('本地模拟仅限开发者工具及本机服务');
        if (!/^[a-zA-Z0-9_-]{1,32}$/.test(this.data.mockName)) throw new Error('模拟身份请输入 1–32 位英文、数字或下划线');
        code = `mock:${this.data.mockName}`;
      } else {
        const result = await new Promise((resolve, reject) => wx.login({ timeout: 10000, success: resolve, fail: () => reject(new Error('微信登录未完成，请检查网络后重试')) }));
        if (!result.code) throw new Error('未取得微信登录凭据，请重试');
        code = result.code;
      }
      getApp().saveLogin(await api.post('/auth/wechat-login', { code }));
      await this.refreshIdentity();
    } catch (error) { this.setData({ error: error.message || '登录未完成，请重试' }); }
    finally { this.setData({ busy: false }); }
  },
  async bindInvitation() {
    if (this.data.busy || !this.data.inviteCode) return;
    this.setData({ busy: true, error: '' });
    try {
      await api.post('/me/binding-requests', { inviteCode: this.data.inviteCode });
      this.setData({ bindingStatus: 'PENDING', inviteCode: '' });
      await this.refreshIdentity();
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false }); }
  },
  logout() { getApp().clearLogin(); this.setData({ loggedIn: false, bindingStatus: 'UNBOUND', person: null, mockEnabled: false }); }
});
