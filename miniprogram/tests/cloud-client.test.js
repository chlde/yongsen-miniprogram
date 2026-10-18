const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createTransport, resolveMediaUrl } = require('../lib/transport');
const { localMockAllowed, authContext } = require('../lib/environment');

const cloudConfig = { transport: 'cloud', cloudEnv: 'production-env', cloudService: 'training', apiPathPrefix: '/api/v1', apiBase: 'https://training.company.cn/api/v1' };
const requestConfig = { transport: 'request', apiBase: 'http://127.0.0.1:8080/api/v1' };
function load(file, globals, overrides = {}) {
  const filename = path.join(__dirname, '..', file), mod = { exports: {} };
  const context = Object.assign({ module: mod, require: name => overrides[name] || require(path.resolve(path.dirname(filename), name)) }, globals);
  context.App = value => { mod.exports = value; };
  context.Page = value => { mod.exports = value; };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), context, { filename });
  return mod.exports;
}
function makeApp(config, saved = new Map(), pages = []) {
  const navigations = [], wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value), removeStorageSync: key => saved.delete(key),
    getDeviceInfo: () => ({ platform: 'devtools' }), getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }), reLaunch: options => navigations.push(options.url) };
  const app = load('app.js', { wx, getCurrentPages: () => pages }, { './lib/config': config, './lib/api': { initialize() {} } });
  return { app, saved, navigations, wx };
}

test('cloud transport initializes once and preserves auth, idempotency, timeout and API path', async () => {
  const calls = [], inits = [];
  const transport = createTransport(cloudConfig, { cloud: { init: options => inits.push(options), callContainer: options => { calls.push(options); options.success({ statusCode: 200, data: { code: 'OK' } }); } } });
  transport.initialize();
  await transport.send('/learning/sessions/s/events:batch', { method: 'POST', data: { batchId: 'b' }, header: { Authorization: 'Bearer opaque', 'Idempotency-Key': 'b' } });
  await transport.send('/me', { header: {} });
  assert.equal(inits.length, 1); assert.equal(inits[0].env, cloudConfig.cloudEnv);
  assert.equal(calls[0].path, '/api/v1/learning/sessions/s/events:batch');
  assert.equal(calls[0].header['X-WX-SERVICE'], 'training'); assert.equal(calls[0].header.Authorization, 'Bearer opaque');
  assert.equal(calls[0].header['Idempotency-Key'], 'b'); assert.equal(calls[0].timeout, 8000); assert.equal(calls[0].data.batchId, 'b');
  assert.equal(calls[0].url, undefined); assert.equal(calls[0].header['X-WX-OPENID'], undefined);
});

test('cloud supports Promise SDK result and wraps failures without HTTP fallback', async () => {
  let calls = 0;
  const transport = createTransport(cloudConfig, { cloud: { init() {}, callContainer: async () => { calls++; if (calls === 1) return { statusCode: 200 }; throw new Error('gateway timeout'); } }, request() { throw new Error('unexpected fallback'); } });
  assert.equal((await transport.send('/me', {})).statusCode, 200);
  await assert.rejects(transport.send('/me', {}), error => error.code === 'NETWORK_ERROR');
});

test('missing cloud settings or SDK reject before any request', async () => {
  for (const config of [{ ...cloudConfig, cloudEnv: '' }, { ...cloudConfig, cloudService: '' }, { ...cloudConfig, apiPathPrefix: '/other' }, { ...cloudConfig, transport: 'typo' }]) {
    await assert.rejects(createTransport(config, {}).send('/me', {}), error => error.code === 'CLIENT_CONFIG_ERROR');
  }
  await assert.rejects(createTransport(cloudConfig, {}).send('/me', {}), /更新微信/);
});

test('HTTP mode remains compatible and application paths cannot change the destination', async () => {
  let sent;
  const transport = createTransport(requestConfig, { request: options => { sent = options; options.success({ statusCode: 200 }); } });
  await transport.send('/me', { header: {} });
  assert.equal(sent.url, 'http://127.0.0.1:8080/api/v1/me'); assert.equal(sent.timeout, 8000);
  for (const route of ['https://other.cn/me', '//other.cn/me', '/../me', '/me#fragment', '/me\\path']) await assert.rejects(transport.send(route, {}), /接口路径无效/);
});

test('media URLs use a separate HTTPS origin; cloud cannot fall back to local HTTP', () => {
  assert.equal(resolveMediaUrl('/api/v1/media/tickets/opaque', cloudConfig), 'https://training.company.cn/api/v1/media/tickets/opaque');
  assert.equal(resolveMediaUrl('https://cdn.company.cn/video?signature=opaque', cloudConfig), 'https://cdn.company.cn/video?signature=opaque');
  assert.equal(resolveMediaUrl('/media/local', requestConfig), 'http://127.0.0.1:8080/media/local');
  for (const url of ['http://cdn.company.cn/video', 'http://127.0.0.1:8080/media', '//other.cn/media', 'cloud://file', 'https://user:pass@cdn.company.cn/video']) assert.throws(() => resolveMediaUrl(url, cloudConfig));
  assert.throws(() => resolveMediaUrl('/media/opaque', { ...cloudConfig, apiBase: requestConfig.apiBase }), /HTTPS/);
});

test('cloud mode never enables mock even if a stale local API base remains', () => {
  assert.equal(localMockAllowed(requestConfig.apiBase, 'devtools', 'develop', 'cloud'), false);
  assert.notEqual(authContext(cloudConfig), authContext({ ...cloudConfig, cloudEnv: 'another-env' }));
});

test('API does not clear a newer login when an older request returns 401', async () => {
  let callback, expired = 0;
  const app = { globalData: { token: 'old' }, handleAuthExpired() { expired++; } };
  const api = load('lib/api.js', { wx: { request: options => { callback = options.success; } }, getApp: () => app }, { './config': requestConfig });
  const pending = api.request('/me'); app.globalData.token = 'new';
  callback({ statusCode: 401, data: { code: 'UNAUTHORIZED' } });
  await assert.rejects(pending, error => error.statusCode === 401 && !error.authExpired);
  assert.equal(app.globalData.token, 'new'); assert.equal(expired, 0);
});

test('API expires only the current authenticated token and accepts cloud JSON responses', async () => {
  let expired = 0, status = 200;
  const app = { globalData: { token: 'current' }, handleAuthExpired(token) { assert.equal(token, 'current'); expired++; this.globalData.token = ''; } };
  const api = load('lib/api.js', { wx: { cloud: { init() {}, callContainer: async () => ({ statusCode: status, data: status === 200 ? '{"code":"OK","data":{"bindingStatus":"UNBOUND"}}' : '<gateway error>' }) } }, getApp: () => app }, { './config': cloudConfig });
  assert.equal((await api.request('/me')).bindingStatus, 'UNBOUND'); status = 401;
  await assert.rejects(api.request('/me'), error => error.authExpired && error.statusCode === 401);
  await assert.rejects(api.request('/auth/wechat-login'), error => !error.authExpired);
  assert.equal(expired, 1);
});

test('changing environment clears cached identity, while a matching context restores it', () => {
  const old = new Map([['yongsen.accessToken', 'old'], ['yongsen.authContext', authContext(requestConfig)], ['yongsen.localMock', true]]);
  const changed = makeApp(cloudConfig, old); changed.app.onLaunch();
  assert.equal(changed.app.globalData.token, ''); assert.equal(old.has('yongsen.accessToken'), false); assert.equal(changed.app.globalData.localMock, false);
  const current = makeApp(cloudConfig, new Map([['yongsen.accessToken', 'valid'], ['yongsen.authContext', authContext(cloudConfig)]])); current.app.onLaunch();
  assert.equal(current.app.globalData.token, 'valid');
});

test('expiry pauses current learning, keeps task entry and navigates to login only once', () => {
  let paused = 0;
  const current = { route: 'pages/learn/index', assignmentId: 'a 1', notificationId: 'n1', onLoginExpired() { paused++; } };
  const { app, navigations } = makeApp(cloudConfig, new Map(), [current]);
  app.globalData.token = 'valid'; app.handleAuthExpired('valid'); app.handleAuthExpired('valid');
  assert.equal(paused, 1); assert.equal(navigations.length, 1); assert.equal(navigations[0], '/pages/login/index');
  assert.equal(app.globalData.pendingTaskRoute, '/pages/task/index?assignmentId=a%201&notificationId=n1');
  assert.equal(app.globalData.bindingStatus, 'UNBOUND');
});

test('native learning expiry disposes sampling and pauses media without sending unauthenticated events', () => {
  let disposed = 0, paused = 0;
  const page = load('pages/learn/index.js', {}, { '../../lib/api': {} });
  page.tracker = { dispose() { disposed++; } }; page.sessionId = 's1'; page._playing = true;
  page.video = { pause() { paused++; } }; page.setData = value => Object.assign(page.data, value);
  page.onLoginExpired();
  assert.equal(disposed, 1); assert.equal(paused, 1); assert.equal(page.tracker, null); assert.equal(page.sessionId, null);
  assert.equal(page.data.playing, false); assert.equal(page.data.needsResume, true);
});

test('login obtains a new wx.login code for each explicit attempt; never persists or retries a code', async () => {
  let loginCalls = 0;
  const posts = [], saved = [];
  const app = { globalData: {}, saveLogin: result => saved.push(result) };
  const page = load('pages/login/index.js', { wx: { login: options => { loginCalls++; assert.equal(options.timeout, 10000); options.success({ code: `single-use-${loginCalls}` }); } }, getApp: () => app },
    { '../../lib/api': { post: async (route, body) => { posts.push({ route, body }); if (posts.length === 1) throw new Error('connection interrupted'); return { accessToken: 'opaque' }; } } });
  page.setData = value => Object.assign(page.data, value); page.refreshIdentity = async () => {};
  await page.login(); assert.equal(loginCalls, 1); assert.equal(saved.length, 0);
  await page.login(); assert.equal(loginCalls, 2); assert.equal(posts[1].body.code, 'single-use-2'); assert.equal(saved.length, 1);
});

test('missing real notification template keeps subscription unavailable without fabricating authorization', async () => {
  let writes = 0;
  const page = load('pages/task/index.js', {}, { '../../lib/api': { request: async () => ({ providerMode: 'wechat', templateId: '' }), post: async () => { writes++; } } });
  page.setData = value => Object.assign(page.data, value);
  await page.prepareSubscription();
  assert.equal(writes, 0); assert.equal(page.data.subscriptionReady, false); assert.equal(page.subscription, null); assert.match(page.subscriptionError, /尚未配置/);
});

test('explicitly unconfigured template blocks subscription even when an old template ID remains', async () => {
  let writes = 0;
  const page = load('pages/task/index.js', {}, { '../../lib/api': { request: async () => ({ providerMode: 'wechat', configured: false, templateId: 'local-mock-template' }), post: async () => { writes++; } } });
  page.setData = value => Object.assign(page.data, value);
  await page.prepareSubscription();
  assert.equal(writes, 0); assert.equal(page.data.subscriptionReady, false); assert.match(page.subscriptionError, /尚未配置/);
});

test('older notification settings without a configured field still prepare a nonempty template', async () => {
  let writes = 0;
  const page = load('pages/task/index.js', {}, { '../../lib/api': { request: async () => ({ providerMode: 'wechat', templateId: 'real-template' }), post: async () => { writes++; return { id: 'r1', templateId: 'real-template', nonce: 'one-use' }; } } });
  page.setData = value => Object.assign(page.data, value);
  await page.prepareSubscription();
  assert.equal(writes, 1); assert.equal(page.data.subscriptionReady, true); assert.equal(page.subscription.id, 'r1');
});
