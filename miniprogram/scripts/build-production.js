const fs = require('node:fs');
const path = require('node:path');
function parseArgs(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = { '--env': 'cloudEnv', '--version': 'clientVersion' }[args[i]];
    if (!key || options[key] || !args[i + 1] || args[i + 1].startsWith('--')) throw new Error('仅接受 --env 与 --version；禁止传入密钥');
    options[key] = args[i + 1];
  }
  return options;
}
function productionConfig(options) {
  if (Object.keys(options).some(key => !['cloudEnv', 'clientVersion'].includes(key))) throw new Error('配置包含非允许字段');
  const cloudEnv = options.cloudEnv || 'yongsen-prod-d4ghriorcc1483d38';
  const clientVersion = options.clientVersion || '2.0.0';
  if (!/^[a-z][a-z0-9-]{5,80}$/.test(cloudEnv) || !/^\d+\.\d+\.\d+$/.test(clientVersion)) throw new Error('云环境或版本号格式无效');
  return { cloudEnv, functionName: 'ys-training', clientVersion };
}
function build(options = {}, root = path.resolve(__dirname, '..')) {
  const config = productionConfig(options);
  const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
  if (project.appid !== 'wx505c1dc50f1f7099') throw new Error('AppID 与永森小程序不一致');
  project.setting = Object.assign({}, project.setting, { urlCheck: true }); project.projectname = 'yongsen-cloudbase-v2';
  fs.mkdirSync(path.join(root, 'release'), { recursive: true });
  const output = fs.mkdtempSync(path.join(root, 'release', 'v2-'));
  for (const name of ['app.js', 'app.json', 'app.wxss', 'sitemap.json', 'lib', 'pages', 'admin']) fs.cpSync(path.join(root, name), path.join(output, name), { recursive: true });
  fs.writeFileSync(path.join(output, 'lib/config.js'), `module.exports = ${JSON.stringify(config, null, 2)};\n`);
  fs.writeFileSync(path.join(output, 'project.config.json'), JSON.stringify(project, null, 2));
  return output;
}
if (require.main === module) { try { console.log(build(parseArgs(process.argv.slice(2)))); } catch (error) { console.error(error.message); process.exitCode = 1; } }
module.exports = { parseArgs, productionConfig, build };
