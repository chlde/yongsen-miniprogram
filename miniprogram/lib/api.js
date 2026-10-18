const config = require('./config');
const { createTransport, resolveMediaUrl } = require('./transport');
const transport = createTransport(config, () => wx);
let counter = 0;
function requestId(prefix) { counter += 1; return `${prefix || 'mp'}-${Date.now()}-${counter}-${Math.random().toString(16).slice(2, 10)}`; }
async function request(path, options) {
  const opts = options || {};
  const app = getApp(), token = app.globalData.token;
  const response = await transport.send(path, {
    method: opts.method || 'GET', data: opts.data,
    header: Object.assign({ 'content-type': 'application/json' }, token ? { Authorization: `Bearer ${token}` } : {}, opts.idempotencyKey ? { 'Idempotency-Key': opts.idempotencyKey } : {})
  });
  let body = response.data;
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch (_) { body = null; } }
  if (!body || typeof body !== 'object' || Array.isArray(body)) body = {};
  if (response.statusCode >= 200 && response.statusCode < 300 && body.code === 'OK') return body.data;
  const error = new Error(response.statusCode === 401 ? '登录已过期，请重新登录' : body.message || `请求失败（${response.statusCode}）`);
  error.code = body.code || 'REQUEST_FAILED'; error.details = body.details || {}; error.statusCode = response.statusCode;
  if (response.statusCode === 401 && token && app.globalData.token === token) {
    error.authExpired = true;
    app.handleAuthExpired(token);
  }
  throw error;
}
function post(path, data, idempotencyKey) { return request(path, { method: 'POST', data: data || {}, idempotencyKey: idempotencyKey || requestId('write') }); }
function absoluteUrl(url) {
  return resolveMediaUrl(url, config);
}
module.exports = { request, post, requestId, absoluteUrl, initialize: transport.initialize };
