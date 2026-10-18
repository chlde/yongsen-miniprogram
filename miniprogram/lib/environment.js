function localMockAllowed(apiBase, platform, envVersion, transport) {
  return (!transport || transport === 'request') && /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?\/api\/v1$/.test(apiBase)
    && platform === 'devtools' && envVersion === 'develop';
}
function authContext(config) {
  return JSON.stringify([config.transport || 'request', config.apiBase || '', config.cloudEnv || '', config.cloudService || '', config.apiPathPrefix || '/api/v1']);
}
module.exports = { localMockAllowed, authContext };
