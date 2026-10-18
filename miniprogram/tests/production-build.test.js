const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { parseArgs, productionConfig, build } = require('../scripts/build-production');
const valid = { cloudEnv: 'production-123', cloudService: 'training', publicHttps: 'https://training.company.cn' };

test('production config preserves cloud mode with public identifiers and the fixed API prefix', () => {
  const result = productionConfig(valid, '1.0.0');
  assert.equal(result.transport, 'cloud'); assert.equal(result.apiBase, 'https://training.company.cn/api/v1'); assert.equal(result.apiPathPrefix, '/api/v1');
  assert.deepEqual(parseArgs(['--env', valid.cloudEnv, '--service', valid.cloudService, '--public-https', valid.publicHttps]), valid);
});

test('SCF HTTPS production config uses wx.request without requiring cloud identifiers', () => {
  const options = { transport: 'https', publicHttps: valid.publicHttps };
  const result = productionConfig(options, '1.0.0');
  assert.deepEqual(result, { transport: 'request', cloudEnv: '', cloudService: '',
    apiPathPrefix: '/api/v1', apiBase: 'https://training.company.cn/api/v1', clientVersion: '1.0.0' });
  assert.deepEqual(parseArgs(['--transport', 'https', '--public-https', valid.publicHttps]), options);
  assert.throws(() => productionConfig({ ...valid, transport: 'https' }, '1.0.0'), /不接受云托管/);
  assert.throws(() => productionConfig({ ...valid, transport: 'http' }, '1.0.0'), /仅支持/);
});

test('HTTPS mode enforces the same production domain policy', () => {
  for (const url of ['http://training.company.cn', 'https://localhost', 'https://example.com', 'https://training.company.cn?secret=hidden']) {
    assert.throws(() => productionConfig({ transport: 'https', publicHttps: url }, '1.0.0'), /HTTPS/);
  }
});

test('production config refuses local, IP, insecure, test, credential and query URLs', () => {
  for (const url of ['http://training.company.cn', 'https://localhost', 'https://127.0.0.1', 'https://192.168.1.1', 'https://[::1]', 'https://test.company.cn', 'https://company.local', 'https://example.com', 'https://training.company.cn:8443', 'https://name:secret@training.company.cn', 'https://training.company.cn?token=hidden', 'https://training.company.cn/admin']) {
    assert.throws(() => productionConfig({ ...valid, publicHttps: url }, '1.0.0'), /HTTPS/);
  }
});

test('build accepts no secrets, extra flags, duplicate fields or missing cloud identifiers', () => {
  for (const args of [['--secret', 'do-not-log'], ['--env', 'ok', '--env', 'again'], ['--env'], ['--env=inline']]) {
    assert.throws(() => parseArgs(args), error => !error.message.includes('do-not-log'));
  }
  assert.throws(() => productionConfig({ ...valid, appSecret: 'do-not-log' }, '1.0.0'), /禁止写入密钥/);
  assert.throws(() => productionConfig({ ...valid, cloudEnv: '' }, '1.0.0'));
  assert.throws(() => productionConfig({ ...valid, cloudEnv: 'a'.repeat(32) }, '1.0.0'));
});

test('build creates an independent upload directory without changing source or copying private config', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yongsen-production-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, 'lib')); fs.mkdirSync(path.join(root, 'pages/login'), { recursive: true });
  const sourceConfig = "module.exports = { apiBase:'http://127.0.0.1:8080/api/v1', clientVersion:'1.0.0' };\n";
  fs.writeFileSync(path.join(root, 'lib/config.js'), sourceConfig);
  fs.writeFileSync(path.join(root, 'lib/api.js'), 'module.exports = {};');
  fs.writeFileSync(path.join(root, 'pages/login/index.wxml'), '<view>Login</view>');
  for (const name of ['app.js', 'app.json', 'app.wxss', 'sitemap.json']) fs.writeFileSync(path.join(root, name), '{}');
  fs.writeFileSync(path.join(root, 'project.config.json'), JSON.stringify({ appid: 'wx505c1dc50f1f7099', setting: { urlCheck: false } }));
  fs.writeFileSync(path.join(root, 'project.private.config.json'), 'must not copy');
  fs.mkdirSync(path.join(root, 'artifacts')); fs.writeFileSync(path.join(root, 'artifacts/private.txt'), 'must not copy');
  const output = build(valid, root);
  assert.ok(output.startsWith(path.join(root, 'release/production-')));
  assert.equal(fs.readFileSync(path.join(root, 'lib/config.js'), 'utf8'), sourceConfig);
  assert.equal(require(path.join(output, 'lib/config.js')).transport, 'cloud');
  assert.equal(JSON.parse(fs.readFileSync(path.join(output, 'project.config.json'))).setting.urlCheck, true);
  for (const name of ['project.private.config.json', 'artifacts', 'node_modules', 'tests', 'scripts']) assert.equal(fs.existsSync(path.join(output, name)), false);
  assert.equal(fs.existsSync(path.join(output, 'pages/login/index.wxml')), true);
  const httpsOutput = build({ transport: 'https', publicHttps: valid.publicHttps }, root);
  const httpsConfig = require(path.join(httpsOutput, 'lib/config.js'));
  assert.notEqual(httpsOutput, output);
  assert.equal(httpsConfig.transport, 'request');
  assert.equal(httpsConfig.cloudEnv, ''); assert.equal(httpsConfig.cloudService, '');
  assert.equal(httpsConfig.apiBase, 'https://training.company.cn/api/v1');
  assert.equal(fs.readFileSync(path.join(root, 'lib/config.js'), 'utf8'), sourceConfig);
});
