// 本地默认；生产配置由 npm run build:production 写入独立 release 目录。
// cloud 模式：API 使用 callContainer；apiBase 的 HTTPS origin 用于组件加载票据媒体。
module.exports = {
  transport: 'request', apiBase: 'http://127.0.0.1:8080/api/v1', clientVersion: '1.0.0',
  cloudEnv: '', cloudService: '', apiPathPrefix: '/api/v1'
};
