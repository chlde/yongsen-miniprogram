const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { randomBytes } = require('node:crypto');

const bootstrap = path.resolve(__dirname, '../scf_bootstrap');
const good = {
  PATH: process.env.PATH, TRAINING_MYSQL_URL: 'jdbc:mysql://10.1.0.8:3306/yongsen_training',
  TRAINING_MYSQL_USER: 'training_app', TRAINING_MYSQL_PASSWORD: randomBytes(20).toString('hex'),
  WECHAT_APPID: 'wx505c1dc50f1f7099', WECHAT_APP_SECRET: randomBytes(16).toString('hex'),
  TRAINING_ADMIN_PASSWORD: randomBytes(20).toString('hex'), TRAINING_DATA_KEY: randomBytes(32).toString('base64url'),
  TRAINING_WORKER_SECRET: randomBytes(32).toString('base64url'), TRAINING_PUBLIC_BASE: 'https://training.company.cn',
  TRAINING_COS_BUCKET: 'training-1234567890', TRAINING_COS_REGION: 'ap-shanghai', TRAINING_SCF_REGION: 'ap-shanghai',
  TRAINING_SCF_WORKER_FUNCTION: 'training-event-bridge', TRAINING_TENCENT_SECRET_ID: 'fixture-cam-id',
  TRAINING_TENCENT_SECRET_KEY: randomBytes(32).toString('base64url')
};
const check = env => spawnSync('sh', [bootstrap, '--check'], { env, encoding: 'utf8' });

test('SCF bootstrap validates a complete config without invoking network, binaries or emitting secrets', () => {
  const result = check(good);
  assert.equal(result.status, 0, result.stderr); assert.match(result.stdout, /validated/);
  for (const key of ['TRAINING_MYSQL_PASSWORD', 'WECHAT_APP_SECRET', 'TRAINING_ADMIN_PASSWORD', 'TRAINING_DATA_KEY', 'TRAINING_WORKER_SECRET', 'TRAINING_TENCENT_SECRET_KEY']) {
    assert.equal((result.stdout + result.stderr).includes(good[key]), false);
  }
});

test('missing cloud credentials and production secrets prevent application startup', () => {
  for (const name of ['TRAINING_MYSQL_PASSWORD', 'WECHAT_APP_SECRET', 'TRAINING_ADMIN_PASSWORD', 'TRAINING_DATA_KEY', 'TRAINING_WORKER_SECRET', 'TRAINING_COS_BUCKET', 'TRAINING_SCF_WORKER_FUNCTION', 'TRAINING_TENCENT_SECRET_ID', 'TRAINING_TENCENT_SECRET_KEY']) {
    const result = check({ ...good, [name]: '' }); assert.notEqual(result.status, 0); assert.match(result.stderr, /required/);
  }
});

test('bootstrap rejects local data paths, nonproduction modes and unsafe origins', () => {
  for (const change of [
    { SPRING_PROFILES_ACTIVE: 'local' }, { SPRING_PROFILES_ACTIVE: 'serverless,local' },
    { WECHAT_MODE: 'mock' }, { TRAINING_STORAGE_MODE: 'local' }, { TRAINING_BACKGROUND_MODE: 'local' },
    { TRAINING_MYSQL_URL: 'jdbc:h2:file:./data' }, { TRAINING_MYSQL_URL: 'jdbc:mysql://127.0.0.1/training' },
    { TRAINING_MYSQL_USER: 'root' }, { TRAINING_PUBLIC_BASE: 'http://training.company.cn' },
    { TRAINING_PUBLIC_BASE: 'https://name:secret@training.company.cn' },
    { TRAINING_PUBLIC_BASE: 'https://training.company.cn?secret=hidden' },
    { TRAINING_COS_REGION: 'ap-beijing' }, { TRAINING_WORKER_SECRET: 'weak' }
  ]) assert.notEqual(check({ ...good, ...change }).status, 0);
});
