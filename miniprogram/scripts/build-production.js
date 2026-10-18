const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');

function parseArgs(args) {
  const result = {};
  const known = { '--transport': 'transport', '--env': 'cloudEnv', '--service': 'cloudService', '--public-https': 'publicHttps' };
  for (let i = 0; i < args.length; i += 2) {
    const key = known[args[i]];
    // Deliberately do not echo unknown arguments: a mistakenly pasted secret stays out of logs.
    if (!key || result[key] || !args[i + 1] || args[i + 1].startsWith('--')) {
      throw new Error('只接受 --transport、--env、--service、--public-https，且各提供一次；不得传入任何密钥');
    }
    result[key] = args[i + 1];
  }
  return result;
}

function productionConfig(options, clientVersion) {
  if (Object.keys(options).some(key => !['transport', 'cloudEnv', 'cloudService', 'publicHttps'].includes(key))) throw new Error('配置包含非允许字段；禁止写入密钥');
  const transport = options.transport || 'cloud';
  if (!['cloud', 'https'].includes(transport)) throw new Error('生产请求方式仅支持 https 或 cloud');
  if (transport === 'https' && ('cloudEnv' in options || 'cloudService' in options)) throw new Error('HTTPS 模式不接受云托管环境或服务参数');
  for (const [name, max] of transport === 'cloud' ? [['cloudEnv', 128], ['cloudService', 63]] : []) {
    const value = options[name];
    if (typeof value !== 'string' || value.length > max || !/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(value) ||
        /secret|password|private.?key|access.?token/i.test(value) || /^(?:[a-f\d]{32}|[a-f\d]{40}|[a-f\d]{64})$/i.test(value)) {
      throw new Error('必须填写实际云环境 ID 和服务名；此构建不接收密钥');
    }
  }
  let url;
  try { url = new URL(options.publicHttps); } catch (_) { throw new Error('必须填写实际生产 HTTPS 域名'); }
  const hostname = url.hostname.toLowerCase();
  const labels = hostname.split('.');
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash ||
      url.port || !['/', '/api/v1', '/api/v1/'].includes(url.pathname) || net.isIP(hostname) ||
      labels.length < 2 || labels.some(label => !/^[a-z\d](?:[a-z\d-]*[a-z\d])?$/.test(label)) ||
      /(^|[.-])(localhost|local|internal|intranet|test|testing|example|invalid|dev|development|staging|stage|sandbox|demo|mock)([.-]|$)/i.test(hostname)) {
    throw new Error('生产媒体地址须为公开 HTTPS 域名，不接受本机、内网 IP、测试地址、凭据或查询参数');
  }
  return { transport: transport === 'https' ? 'request' : 'cloud',
    cloudEnv: transport === 'cloud' ? options.cloudEnv : '', cloudService: transport === 'cloud' ? options.cloudService : '',
    apiPathPrefix: '/api/v1', apiBase: `${url.origin}/api/v1`, clientVersion };
}

function copySourceTree(source, destination) {
  fs.mkdirSync(destination, { recursive: true });
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const from = path.join(source, entry.name), to = path.join(destination, entry.name);
    if (entry.isSymbolicLink()) throw new Error('生产源文件不得使用符号链接');
    if (entry.isDirectory()) copySourceTree(from, to);
    else if (/\.(js|json|wxml|wxss|png|jpg|jpeg|svg|webp)$/.test(entry.name) && entry.name !== 'config.js') fs.copyFileSync(from, to);
  }
}

function build(options, root = path.resolve(__dirname, '..')) {
  const localConfig = require(path.join(root, 'lib/config.js'));
  const config = productionConfig(options, localConfig.clientVersion);
  const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'));
  if (!/^wx[a-f\d]{16}$/i.test(project.appid)) throw new Error('项目必须先配置真实 AppID；构建不会填入占位 AppID');
  project.setting = Object.assign({}, project.setting, { urlCheck: true });
  project.projectname = 'yongsen-training-production';
  const releases = path.join(root, 'release');
  fs.mkdirSync(releases, { recursive: true });
  const output = fs.mkdtempSync(path.join(releases, 'production-'));
  try {
    for (const file of ['app.js', 'app.json', 'app.wxss', 'sitemap.json']) fs.copyFileSync(path.join(root, file), path.join(output, file));
    for (const folder of ['lib', 'pages']) copySourceTree(path.join(root, folder), path.join(output, folder));
    fs.writeFileSync(path.join(output, 'lib/config.js'), `// Generated deployment config: public identifiers only.\nmodule.exports = ${JSON.stringify(config, null, 2)};\n`);
    fs.writeFileSync(path.join(output, 'project.config.json'), `${JSON.stringify(project, null, 2)}\n`);
    fs.writeFileSync(path.join(output, 'DEPLOYMENT.txt'), '生产导入目录。未部署、未上传、未验收。真实微信登录、媒体 HTTPS 访问和模板送达仍须实际验证。\n');
    return output;
  } catch (error) {
    fs.rmSync(output, { recursive: true, force: true });
    throw error;
  }
}

if (require.main === module) {
  try { console.log(`生产工程已生成（未部署）：${build(parseArgs(process.argv.slice(2)))}`); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { parseArgs, productionConfig, build };
