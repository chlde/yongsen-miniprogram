import { spawn } from 'node:child_process';
import { copyFile, mkdir } from 'node:fs/promises';
import { createServer } from 'node:net';
import path from 'node:path';
import { root, java, runtimeEnv, backendJar, waitFor, stop } from './process.mjs';

const dataDir = path.resolve(process.env.TRAINING_DATA_DIR || path.join(root, '.local', 'dev'));
const backendPort = process.env.SERVER_PORT || '8080';
const webPort = process.env.TRAINING_WEB_PORT || '5173';
for (const port of [backendPort, webPort]) {
    if (!/^\d+$/.test(port) || Number(port) < 1024 || Number(port) > 65535) throw new Error(`Invalid local port: ${port}`);
    await new Promise((resolve, reject) => {
        const probe = createServer();
        probe.once('error', () => reject(new Error(`Local port ${port} is in use. Stop the prior server or choose another port.`)));
        probe.listen(Number(port), '127.0.0.1', () => probe.close(resolve));
    });
}
await mkdir(dataDir, { recursive: true });
const runnableJar = path.join(dataDir, 'training-backend.jar');
await copyFile(backendJar(), runnableJar);
const backend = spawn(java, ['-jar', runnableJar], {
    cwd: path.join(root, 'backend'), stdio: 'inherit',
    env: { ...runtimeEnv, SPRING_PROFILES_ACTIVE: 'local', WECHAT_MODE: 'mock', TRAINING_DATA_DIR: dataDir, SERVER_PORT: backendPort }
});
let frontend;
let stopping = false;
async function cleanup() {
    if (stopping) {
        return;
    }
    stopping = true;
    await Promise.all([stop(frontend), stop(backend)]);
}
process.on('SIGINT', () => { cleanup().finally(() => process.exit(0)); });
process.on('SIGTERM', () => { cleanup().finally(() => process.exit(0)); });
try {
    await waitFor(`http://127.0.0.1:${backendPort}/health`, [backend]);
    frontend = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', webPort, '--strictPort'], {
        cwd: path.join(root, 'web'), stdio: 'inherit',
        env: { ...runtimeEnv, VITE_API_BASE_URL: `http://127.0.0.1:${backendPort}/api/v1` }
    });
    await waitFor(`http://127.0.0.1:${webPort}`, [backend, frontend]);
    console.log(`\n永森油服本地预览：http://127.0.0.1:${webPort}\n数据目录：${dataDir}\n微信渠道：本地模拟，不会向真实微信发送。按 Ctrl+C 停止。`);
    await Promise.race([backend, frontend].map(child => new Promise(resolve => child.once('exit', resolve))));
    await cleanup();
} catch (error) {
    console.error(error.message);
    await cleanup();
    process.exitCode = 1;
}
