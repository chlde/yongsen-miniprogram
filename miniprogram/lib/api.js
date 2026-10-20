const config = require('./config');
let initialized = false, counter = 0;
function initialize() {
  if (initialized) return;
  if (!wx.cloud) throw new Error('请升级微信后使用企业培训');
  wx.cloud.init({ env: config.cloudEnv, traceUser: true }); initialized = true;
}
function requestId(prefix) { return `${prefix || 'mp'}-${Date.now()}-${++counter}-${Math.random().toString(16).slice(2)}`; }
async function call(action, data, key) {
  initialize();
  let response;
  try { response = await wx.cloud.callFunction({ name: config.functionName, data: { action, data: data || {}, requestId: key || requestId(action) }, config: { env: config.cloudEnv } }); }
  catch (cause) { const error = new Error('云服务暂时无法连接，请检查网络后重试'); error.code = 'NETWORK_ERROR'; error.cause = cause; throw error; }
  const result = response.result;
  if (result && result.code === 'OK') return result.data;
  const error = new Error(result && result.message || '云端处理未完成');
  error.code = result && result.code || 'CLOUD_ERROR'; error.details = result && result.details || {};
  if (['UNAUTHENTICATED', 'ACCOUNT_DISABLED'].includes(error.code)) { error.authExpired = true; getApp().handleAuthExpired(); }
  throw error;
}
function db() { initialize(); return wx.cloud.database({ env: config.cloudEnv }); }
async function list(table, where) {
  const items = []; let after = '';
  for (;;) {
    const database = db();
    const filter = Object.assign({}, where, after ? { _id: database.command.gt(after) } : {});
    const result = await database.collection(`ys_${table}`).where(filter).orderBy('_id', 'asc').limit(20).get();
    items.push(...result.data.map(doc => Object.assign({}, doc, { id: doc._id })));
    if (result.data.length < 20) return { items };
    after = result.data[result.data.length - 1]._id;
  }
}
function watch(table, where, onChange, onError) { return db().collection(`ys_${table}`).where(where).watch({ onChange, onError }); }
async function request(route, options) {
  const data = options && options.data || {}, key = options && options.idempotencyKey;
  if (route === '/me') { const identity = await call('me'); getApp().saveLogin(identity); return identity; }
  if (route === '/me/assignments') return list('assignments', { _openid: '{openid}', visible: true });
  if (route === '/me/binding-requests') return call('bind', data, key);
  if (route === '/me/notification-settings') return call('notificationSettings');
  if (route === '/me/subscriptions') return call('prepareSubscription');
  let match = route.match(/^\/me\/subscriptions\/([^/]+)\/result$/);
  if (match) return call('subscriptionResult', Object.assign({}, data, { id: match[1] }), key);
  match = route.match(/^\/me\/assignments\/([^/]+)(?:\/([^/]+))?$/);
  if (match) {
    const action = { entered: 'entered', 'media-ticket': 'mediaTicket', confirm: 'confirm' }[match[2]] || 'assignmentDetail';
    return call(action, Object.assign({}, data, { assignmentId: match[1] }), key);
  }
  if (route === '/learning/sessions') return call('startSession', data, key);
  match = route.match(/^\/learning\/sessions\/([^/]+)\/([^/]+)$/);
  if (match) { const action = { 'events:batch': 'ingest', close: 'closeSession', takeover: 'takeoverSession' }[match[2]]; if (action) return call(action, Object.assign({}, data, { sessionId: match[1] }), key); }
  throw new Error('不支持的操作');
}
function post(route, data, key) { return request(route, { data, idempotencyKey: key || requestId('write') }); }
function absoluteUrl(url) { if (!/^https:\/\//.test(url || '')) throw new Error('教材地址无效'); return url; }
async function upload(file, title) {
  const type = /\.pdf$/i.test(file.name) ? 'PDF' : /\.mp4$/i.test(file.name) ? 'MP4' : '';
  if (!type) throw new Error('请选择 PDF 或 MP4 文件');
  const ticket = await call('prepareMaterial', { title, type, size: file.size });
  const contents = await new Promise((resolve, reject) => wx.getFileSystemManager().readFile({ filePath: file.path, success: result => resolve(result.data), fail: reject }));
  await new Promise((resolve, reject) => wx.request({ url: ticket.uploadUrl, method: 'PUT', data: contents,
    header: Object.assign({ 'content-type': type === 'PDF' ? 'application/pdf' : 'video/mp4' }, ticket.uploadHeaders), timeout: 120000,
    success: result => result.statusCode >= 200 && result.statusCode < 300 ? resolve() : reject(new Error('教材上传失败，请重试')), fail: reject }));
  await call('finishMaterial', { materialId: ticket.materialId }); return ticket.materialId;
}
module.exports = { initialize, requestId, call, list, watch, request, post, absoluteUrl, upload };
