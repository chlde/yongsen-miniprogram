'use strict';
const fs = require('node:fs');
const path = require('node:path');
const CloudBase = require('@cloudbase/manager-node');
const { rules, storage, functionRules } = require('../security-rules.cjs');
const indexes = require('../indexes.cjs');
async function ensureIndexes(manager) {
  for (const [table, definitions] of Object.entries(indexes)) {
    const current = await manager.database.describeCollection(table);
    const missing = definitions.map(keys => ({ IndexName: keys.join('_').replace(/__/g, '_'), MgoKeySchema: { MgoIndexKeys: keys.map(Name => ({ Name, Direction: '1' })), MgoIsUnique: false } }))
      .filter(index => !current.Indexes.some(existing => existing.Name === index.IndexName));
    if (missing.length) await manager.database.updateCollection(table, { CreateIndexes: missing });
  }
}
async function verifyBuild(manager, kind) {
  const name = `ys-${kind}`;
  const expected = JSON.parse(fs.readFileSync(path.resolve(__dirname, `../dist/${name}/build-info.json`)));
  for (let attempt = 0; attempt < 30; attempt++) {
    const result = await manager.functions.invokeFunction(name, { action: 'health' });
    const response = JSON.parse(result.RetMsg);
    if (response.build && response.build.sourceHash === expected.sourceHash && (kind !== 'materials' || response.rendererReady)) return;
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  throw new Error(`${name}: deployed code or runtime self-check did not match the build`);
}
async function applyFunctionRules(manager) {
  // Function rules are one environment-level map, not individual resources.
  // Preserve other functions in the environment while setting our three names.
  const previous = await manager.permission.describeResourcePermission({ resourceType: 'function' });
  const entry = previous.Data.PermissionList.find(item => item.Resource === '');
  const current = entry && entry.SecurityRule ? JSON.parse(entry.SecurityRule) : { '*': { invoke: false } };
  const desired = { ...current, ...functionRules };
  await manager.permission.modifyResourcePermission({ resourceType: 'function', permission: 'CUSTOM', securityRule: JSON.stringify(desired) });
  const result = await manager.permission.describeResourcePermission({ resourceType: 'function' });
  const applied = JSON.parse(result.Data.PermissionList.find(item => item.Resource === '').SecurityRule);
  for (const [name, rule] of Object.entries(functionRules)) {
    if (!applied[name] || applied[name].invoke !== rule.invoke) throw new Error(`${name}: function permission readback mismatch`);
  }
}
async function deploy({ secretId, secretKey, envId, region = 'ap-shanghai', functions = true, database = true, releaseState = process.env.WECHAT_RELEASE_STATE || 'formal' }) {
  if (envId !== 'yongsen-prod-d4ghriorcc1483d38') throw new Error('Unexpected deployment environment');
  if (!['developer', 'trial', 'formal'].includes(releaseState)) throw new Error('Invalid WeChat release state');
  const manager = new CloudBase({ secretId, secretKey, envId, region });
  if (database) {
    for (const [name, rule] of Object.entries(rules)) {
      await manager.database.createCollectionIfNotExists(name);
      await manager.commonService('tcb', '2018-06-08').call({ Action: 'ModifySafeRule', Param: { EnvId: envId, CollectionName: name, AclTag: 'CUSTOM', Rule: JSON.stringify(rule) } });
      console.log(`database ${name}: rules applied`);
    }
    await manager.storage.setStorageAcl('CUSTOM', storage);
    await ensureIndexes(manager);
  }
  if (functions) {
    const existing = await manager.functions.listFunctions(100, 0);
    for (const kind of functions === true ? ['training', 'materials', 'jobs'] : functions) {
      const name = `ys-${kind}`;
      const func = { name, runtime: 'Nodejs20.19', handler: 'index.main', timeout: kind === 'training' ? 60 : 180, memorySize: kind === 'materials' ? 2048 : 512, installDependency: true, isWaitInstall: true,
        envVariables: { WECHAT_RELEASE_STATE: releaseState } };
      const args = { func, functionRootPath: path.resolve(__dirname, '../dist'), deployMode: 'zip' };
      if (existing.some(item => item.FunctionName === name)) {
        // Dependency installation belongs to the code update. Passing it again
        // with a configuration update can race the newly installed package.
        const { installDependency, isWaitInstall, ...configuration } = func;
        await manager.functions.updateFunctionConfig(configuration);
        await manager.functions.waitFunctionActive(name);
        await manager.functions.updateFunctionCode(args);
        await manager.functions.waitFunctionActive(name);
      } else await manager.functions.createFunction(args);
      await verifyBuild(manager, kind);
      console.log(`function ${name}: deployed build verified`);
      if (kind === 'jobs') {
        const detail = await manager.functions.getFunctionDetail(name);
        if (!(detail.Triggers || []).some(trigger => trigger.TriggerName === 'training-every-minute')) {
          await manager.functions.createFunctionTriggers(name, [{ name: 'training-every-minute', type: 'timer', config: '0 * * * * * *' }]);
        }
      }
    }
    await applyFunctionRules(manager);
  }
  return manager;
}
module.exports = { deploy, ensureIndexes, verifyBuild, applyFunctionRules };
if (require.main === module) {
  deploy({ secretId: process.env.TENCENTCLOUD_SECRETID, secretKey: process.env.TENCENTCLOUD_SECRETKEY, envId: process.env.CLOUDBASE_ENV_ID }).catch(error => { console.error(error.code, error.message); process.exitCode = 1; });
}
