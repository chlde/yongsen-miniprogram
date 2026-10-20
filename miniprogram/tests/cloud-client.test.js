const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function load(wx) {
  const module = { exports: {} }, app = { saveLogin(me) { this.identity = me; }, handleAuthExpired() { this.expired = true; } };
  vm.runInNewContext(fs.readFileSync(require.resolve('../lib/api'), 'utf8'), { module, require: () => ({ cloudEnv: 'environment', functionName: 'ys-training' }), wx, getApp: () => app });
  return { api: module.exports, app };
}
test('native cloud requests pass no business bearer token and preserve command IDs', async () => {
  const sent = [], inits = [];
  const { api, app } = load({ cloud: { init: value => inits.push(value), callFunction: async value => { sent.push(value); return { result: { code: 'OK', data: { role: 'USER' } } }; } } });
  await api.request('/me'); await api.post('/learning/sessions/s/events:batch', { batchId: 'b', events: [] }, 'b');
  assert.equal(inits.length, 1); assert.equal(app.identity.role, 'USER');
  assert.equal(sent[1].data.action, 'ingest'); assert.equal(sent[1].data.requestId, 'b'); assert.equal(sent[1].data.data.sessionId, 's');
  assert.equal(sent[1].header, undefined);
});
test('permission errors propagate and never fall back to HTTP', async () => {
  const { api } = load({ cloud: { init() {}, callFunction: async () => ({ result: { code: 'FORBIDDEN', message: '需要管理员权限' } }) }, request() { assert.fail('no HTTP fallback'); } });
  await assert.rejects(api.call('savePerson'), error => error.code === 'FORBIDDEN');
});
test('media must use HTTPS and unknown actions are rejected locally', async () => {
  const { api } = load({ cloud: { init() {} } });
  assert.throws(() => api.absoluteUrl('http://localhost/a'), /无效/);
  await assert.rejects(api.request('/legacy-auth'), /不支持/);
});
