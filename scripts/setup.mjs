import { root, run, runtimeEnv } from './process.mjs';
import path from 'node:path';

await run('npm', ['ci', '--no-fund', '--no-audit'], { cwd: path.join(root, 'web') });
await run('npm', ['ci', '--no-fund', '--no-audit'], { cwd: path.join(root, 'miniprogram') });
await run('mvn', ['-B', 'package'], { cwd: path.join(root, 'backend'), env: runtimeEnv });
await run('node', ['scripts/create-fixtures.mjs']);
console.log('Setup complete. Start with npm run dev.');
