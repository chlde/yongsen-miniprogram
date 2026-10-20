const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function learningPage(request) {
  let page;
  const routes = [];
  const app = { globalData: { identity: { role: 'USER' } } };
  const api = { request, post: async () => ({ url: '/media/test' }), absoluteUrl: value => value };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/learn/index.js'), 'utf8'), {
    require: name => name.endsWith('/api') ? api : { enrichMaterial: value => value },
    Page: value => { page = value; },
    getApp: () => app,
    wx: {
      reLaunch: ({ url }) => routes.push(url),
      switchTab: ({ url }) => routes.push(url),
      getWindowInfo: () => ({ windowHeight: 800 })
    }
  });
  Object.assign(page, {
    assignmentId: 'assignment-1', taskMaterialId: 'material-1',
    setData(data) { Object.assign(this.data, data); }
  });
  return { page, app, routes, api };
}

test('failed startup exposes a recoverable error and retry loads the material', async () => {
  let attempts = 0;
  let resolveRetry;
  const { page } = learningPage(async () => {
    attempts += 1;
    if (attempts === 1) throw new Error('连接失败，请检查网络后重试');
    return new Promise(resolve => { resolveRetry = resolve; });
  });
  await page.load();
  assert.equal(page.data.loading, false);
  assert.equal(page.data.material, null);
  assert.match(page.data.error, /连接失败/);
  const retry = page.load();
  assert.equal(page.data.loading, true);
  assert.equal(page.data.error, '');
  await page.load();
  assert.equal(attempts, 2, 'repeated retry taps do not duplicate the request');
  resolveRetry({ materials: [{ taskMaterialId: 'material-1', type: 'PDF', pageCount: 1 }] });
  await retry;
  assert.equal(page.data.loading, false);
  assert.equal(page.data.material.type, 'PDF');
  assert.equal(page.data.mediaUrl, '/media/test');
});

test('missing material and stopped tasks finish loading with a visible error state', async () => {
  for (const [task, message] of [
    [{ materials: [] }, /未找到/],
    [{ taskStatus: 'CANCELED', materials: [{ taskMaterialId: 'material-1' }] }, /任务已停止/],
    [{ taskStatus: 'CLOSED', materials: [{ taskMaterialId: 'material-1' }] }, /任务已停止/]
  ]) {
    const { page } = learningPage(async () => task);
    await page.load();
    assert.equal(page.data.loading, false);
    assert.equal(page.data.material, null);
    assert.match(page.data.error, message);
  }
});

test('incomplete learning links make no API request and allow return to the task list', async () => {
  const { page, routes } = learningPage(() => assert.fail('invalid link must not call the API'));
  page.taskMaterialId = undefined;
  await page.load();
  assert.match(page.data.error, /学习入口不完整/);
  assert.equal(page.data.loading, false);
  page.backToTasks();
  assert.deepEqual(routes, ['/pages/tasks/index']);
});

test('unauthenticated learning entry preserves its task and returns to login', async () => {
  const { page, app, routes } = learningPage(() => assert.fail('anonymous learning must not call the API'));
  app.globalData.identity = null;
  await page.load();
  assert.deepEqual(routes, ['/pages/login/index']);
  assert.equal(app.globalData.pendingTaskRoute, '/pages/task/index?assignmentId=assignment-1');
});

test('a late startup response does not update an unloaded page or request media', async () => {
  let resolveTask;
  const { page, api } = learningPage(() => new Promise(resolve => { resolveTask = resolve; }));
  api.post = () => assert.fail('unloaded page must not request media');
  const loading = page.load();
  page._unloaded = true;
  page.setData = () => assert.fail('unloaded page must not update its view');
  resolveTask({ materials: [{ taskMaterialId: 'material-1', type: 'PDF' }] });
  await loading;
});
