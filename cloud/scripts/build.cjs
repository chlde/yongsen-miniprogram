'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const root = path.resolve(__dirname, '..'), output = path.join(root, 'dist');
const allDependencies = require('../package.json').dependencies;
for (const kind of ['training', 'jobs', 'materials']) {
  const target = path.join(output, `ys-${kind}`);
  fs.mkdirSync(target, { recursive: true });
  fs.cpSync(path.join(root, 'src'), path.join(target, 'src'), { recursive: true });
  fs.copyFileSync(path.join(root, 'functions', kind, 'index.js'), path.join(target, 'index.js'));
  const dependencies = { 'wx-server-sdk': allDependencies['wx-server-sdk'], '@cloudbase/node-sdk': '3.17.2' };
  if (kind === 'materials') Object.assign(dependencies, {
    'pdfjs-dist': allDependencies['pdfjs-dist'], '@napi-rs/canvas': allDependencies['@napi-rs/canvas'],
    // Cloud installation can omit optional native packages on an update.
    // SCF uses Linux x64/glibc; make its required renderer binding explicit.
    '@napi-rs/canvas-linux-x64-gnu': allDependencies['@napi-rs/canvas'], mp4box: allDependencies.mp4box
  });
  fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name: `ys-${kind}`, version: '2.0.0', main: 'index.js', dependencies }, null, 2));
  fs.writeFileSync(path.join(target, 'config.json'), JSON.stringify({ permissions: { openapi: kind === 'jobs' || kind === 'training' ? ['subscribeMessage.send'] : [] } }, null, 2));
  const hash = createHash('sha256');
  for (const name of fs.readdirSync(path.join(root, 'src')).sort()) hash.update(name).update(fs.readFileSync(path.join(root, 'src', name)));
  hash.update(fs.readFileSync(path.join(target, 'index.js'))).update(JSON.stringify(dependencies));
  fs.writeFileSync(path.join(target, 'build-info.json'), JSON.stringify({ version: '2.0.0', sourceHash: hash.digest('hex') }));
}
console.log(output);
