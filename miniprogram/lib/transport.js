const REQUEST_TIMEOUT_MS = 8000;

function configError(message) {
  const error = new Error(message);
  error.code = 'CLIENT_CONFIG_ERROR';
  return error;
}

function createTransport(config, runtime) {
  let initialized = false;
  const getRuntime = typeof runtime === 'function' ? runtime : () => runtime;
  const mode = config.transport || 'request';
  function initialize() {
    if (mode === 'request') return;
    if (mode !== 'cloud') throw configError('请求方式配置无效，请联系管理员');
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$/.test(config.cloudEnv || '') ||
        !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$/.test(config.cloudService || '') ||
        config.apiPathPrefix !== '/api/v1') throw configError('云环境或服务尚未配置，请联系管理员');
    const sdk = getRuntime().cloud;
    if (!sdk || typeof sdk.init !== 'function' || typeof sdk.callContainer !== 'function') {
      throw configError('当前微信不支持云托管，请更新微信后重试');
    }
    if (!initialized) {
      sdk.init({ env: config.cloudEnv });
      initialized = true;
    }
  }
  function send(path, options) {
    return new Promise((resolve, reject) => {
      // API callers supply an application path, never an arbitrary destination URL.
      if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || /[\\\r\n#]/.test(path) || path.split('?')[0].split('/').includes('..')) {
        reject(configError('接口路径无效')); return;
      }
      initialize();
      const wxApi = getRuntime();
      let settled = false;
      const success = response => { if (!settled) { settled = true; resolve(response); } };
      const fail = cause => {
        if (settled) return;
        settled = true;
        const error = new Error('连接失败，请检查网络后重试');
        error.code = 'NETWORK_ERROR'; error.cause = cause; reject(error);
      };
      const args = { method: options.method || 'GET', data: options.data, timeout: REQUEST_TIMEOUT_MS, header: options.header, success, fail };
      if (mode === 'cloud') {
        const result = wxApi.cloud.callContainer(Object.assign({}, args, {
          config: { env: config.cloudEnv }, path: `${config.apiPathPrefix}${path}`,
          header: Object.assign({}, options.header, { 'X-WX-SERVICE': config.cloudService })
        }));
        // Both callback and Promise forms exist in supported SDKs. Settle only once.
        if (result && typeof result.then === 'function') result.then(success, fail);
      } else {
        if (!/^https?:\/\//.test(config.apiBase || '')) throw configError('接口地址尚未配置，请联系管理员');
        wxApi.request(Object.assign({}, args, { url: `${config.apiBase}${path}` }));
      }
    });
  }
  return { initialize, send };
}

function resolveMediaUrl(url, config) {
  if (!url) return '';
  const allowed = value => /^https:\/\/[^\s/?#@]+(?:[/?#]|$)/.test(value) ||
    ((config.transport || 'request') === 'request' && /^http:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?(?:[/?#]|$)/.test(value));
  if (/^https?:\/\//.test(url)) {
    if (allowed(url)) return url;
    throw configError('学习材料地址必须使用 HTTPS');
  }
  if (!url.startsWith('/') || url.startsWith('//') || /[\\\s]/.test(url)) throw configError('学习材料地址无效');
  const base = (config.apiBase || '').replace(/\/api\/v1\/?$/, '');
  if (!allowed(base) || !/^https?:\/\/[^/?#]+$/.test(base)) throw configError('学习材料 HTTPS 地址尚未配置，请联系管理员');
  return `${base}${url}`;
}

module.exports = { createTransport, resolveMediaUrl, REQUEST_TIMEOUT_MS };
