const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { build, productionConfig, parseArgs } = require('../scripts/build-production');
test('release contains native admin pages and no backend endpoint or credentials', () => {
  const output = build();
  try {
    const config = require(path.join(output, 'lib/config.js'));
    assert.equal(config.functionName, 'ys-training'); assert.equal(config.apiBase, undefined); assert.equal(config.cloudService, undefined);
    assert.ok(fs.existsSync(path.join(output, 'admin/home/index.wxml')));
    assert.equal(require(path.join(output, 'project.config.json')).setting.urlCheck, true);
  } finally { fs.rmSync(output, { recursive: true, force: true }); }
});
test('release parser accepts only public deployment identifiers', () => {
  assert.throws(() => parseArgs(['--secret', 'value']), /仅接受/);
  assert.throws(() => productionConfig({ secretKey: 'value' }), /非允许/);
  assert.equal(productionConfig({}).clientVersion, '2.0.0');
});
