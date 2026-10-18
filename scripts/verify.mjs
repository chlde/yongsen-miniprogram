import { spawn } from 'node:child_process';
import { cp, copyFile, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { root, java, runtimeEnv, backendJar, run, waitFor, stop } from './process.mjs';

const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const directory = path.join(root, '.local', 'verification', stamp);
const dataDir = path.join(directory, 'data');
const evidenceDir = path.join(directory, 'evidence');
await mkdir(evidenceDir, { recursive: true });
const apiPort = process.env.E2E_API_PORT || '18080';
const webPort = process.env.E2E_WEB_PORT || '15173';
const apiUrl = `http://127.0.0.1:${apiPort}`;
const webUrl = `http://127.0.0.1:${webPort}`;
const results = [];
let backendSha256;
const runnableJar = path.join(directory, 'training-backend.jar');
let backend;
let frontend;
const streams = [];

function service(command, args, cwd, env, name) {
    const log = createWriteStream(path.join(directory, `${name}.log`));
    streams.push(log);
    const child = spawn(command, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.pipe(log, { end: false });
    child.stderr.pipe(log, { end: false });
    return child;
}

function startBackend(storage, name = 'backend') {
    return service(java, ['-jar', runnableJar], path.join(root, 'backend'), {
        ...runtimeEnv, SPRING_PROFILES_ACTIVE: 'local', WECHAT_MODE: 'mock',
        TRAINING_DATA_DIR: storage, SERVER_PORT: apiPort, TRAINING_PUBLIC_BASE: apiUrl
    }, name);
}

async function check(name, operation) {
    const startedAt = new Date().toISOString();
    try {
        await operation();
        results.push({ name, status: 'PASSED', startedAt, finishedAt: new Date().toISOString() });
    } catch (error) {
        results.push({ name, status: 'FAILED', startedAt, message: error.message });
        throw error;
    }
}

async function verifyRestoredData() {
    const checkpoint = JSON.parse(await readFile(path.join(evidenceDir, 'persistence-check.json'), 'utf8'));
    const loginResponse = await fetch(`${apiUrl}/api/v1/admin/auth/login`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'admin', password: process.env.E2E_ADMIN_PASSWORD || 'LocalTrain123!' })
    });
    const login = await loginResponse.json();
    if (!loginResponse.ok || !login.data?.accessToken) {
        throw new Error('Restored backend admin login failed');
    }
    const headers = { Authorization: `Bearer ${login.data.accessToken}` };
    const onboardingResponse = await fetch(`${apiUrl}/api/v1/admin/onboarding`, { headers });
    const onboarding = (await onboardingResponse.json()).data;
    if (!onboardingResponse.ok || onboarding?.tutorialId !== 'admin-basics-v1' || onboarding?.status !== 'COMPLETED') {
        throw new Error('Admin tutorial completion did not survive database backup and restore');
    }
    const response = await fetch(`${apiUrl}/api/v1/admin/tasks/${checkpoint.taskId}/assignments`, { headers });
    const records = await response.json();
    const record = records.data?.items?.find(item => item.id === checkpoint.assignmentId);
    if (!response.ok || record?.status !== 'COMPLETED' || record?.completedAt !== checkpoint.completedAt) {
        throw new Error('Completed training evidence did not survive database backup and restore');
    }
    const csv = await fetch(`${apiUrl}/api/v1/admin/tasks/${checkpoint.taskId}/records.csv`, { headers });
    if (!csv.ok || !(await csv.text()).includes(checkpoint.personNo)) {
        throw new Error('CSV export failed after restoration');
    }
    const printed = await fetch(`${apiUrl}/api/v1/admin/assignments/${checkpoint.assignmentId}/print`, { headers });
    if (!printed.ok || !(await printed.text()).includes(checkpoint.personNo)) {
        throw new Error('Print record failed after restoration');
    }
    for (const material of checkpoint.materials || []) {
        const metadata = await fetch(`${apiUrl}/api/v1/admin/materials/${material.id}`, { headers });
        const saved = (await metadata.json()).data;
        if (!metadata.ok || saved.sha256 !== material.sha256 || saved.pageCount !== material.pageCount) {
            throw new Error('Material identity changed after restoration');
        }
        const ticketResponse = await fetch(`${apiUrl}/api/v1/admin/materials/${material.id}/media-ticket`, {
            method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: '{}'
        });
        const ticket = (await ticketResponse.json()).data;
        const media = await fetch(ticket.url);
        const bytes = Buffer.from(await media.arrayBuffer());
        if (!media.ok || bytes.length === 0) throw new Error('Restored media cannot be read');
        if (material.type === 'MP4' && createHash('sha256').update(bytes).digest('hex') !== material.sha256) {
            throw new Error('Original media content changed after restoration');
        }
    }
}

async function fileManifest(folder, relative = '') {
    const result = [];
    for (const entry of await readdir(path.join(folder, relative), { withFileTypes: true })) {
        const name = path.join(relative, entry.name);
        if (entry.isDirectory()) result.push(...await fileManifest(folder, name));
        else if (entry.isFile()) {
            const bytes = await readFile(path.join(folder, name));
            result.push({ file: name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
        }
    }
    return result.sort((left, right) => left.file.localeCompare(right.file));
}

try {
    await check('backend-tests-and-build', () => run('mvn', ['-B', 'package'], { cwd: path.join(root, 'backend') }));
    await copyFile(backendJar(), runnableJar);
    backendSha256 = createHash('sha256').update(await readFile(runnableJar)).digest('hex');
    await check('web-tests', () => run('npm', ['test'], { cwd: path.join(root, 'web') }));
    await check('web-production-build', () => run('npm', ['run', 'build'], { cwd: path.join(root, 'web') }));
    await check('mini-program-unit-tests', () => run('npm', ['test'], { cwd: path.join(root, 'miniprogram') }));
    await check('serverless-deployment-contracts', () => run('node', ['--test',
        'deployment/serverless/tests/bootstrap.test.cjs', 'deployment/serverless/tests/event-bridge.test.cjs',
        'deployment/serverless/tests/cloudbase-gateway.test.cjs']));
    await check('test-fixtures', () => run('node', ['scripts/create-fixtures.mjs']));
    backend = startBackend(dataDir);
    await waitFor(`${apiUrl}/health`, [backend]);
    frontend = service(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', webPort, '--strictPort'], path.join(root, 'web'), {
        ...runtimeEnv, VITE_API_BASE_URL: `${apiUrl}/api/v1`
    }, 'web');
    await waitFor(webUrl, [backend, frontend]);
    await check('local-end-to-end', () => run('npm', ['run', 'test:e2e'], {
        env: { ...runtimeEnv, E2E_API_URL: apiUrl, E2E_WEB_URL: webUrl, TRAINING_EVIDENCE_DIR: evidenceDir,
            E2E_RESULTS_DIR: path.join(directory, 'browser-results'), E2E_HTML_REPORT: path.join(directory, 'browser-report') }
    }));
    await stop(frontend);
    await stop(backend);
    await check('backup-and-restore', async () => {
        // Offline copy avoids corrupting an H2 file while the database is open.
        const backup = path.join(directory, 'backup');
        const restored = path.join(directory, 'restored');
        const started = Date.now();
        const originalManifest = await fileManifest(dataDir);
        await cp(dataDir, backup, { recursive: true });
        await cp(backup, restored, { recursive: true });
        if (JSON.stringify(originalManifest) !== JSON.stringify(await fileManifest(restored))) {
            throw new Error('Offline database or private material copy differs from source');
        }
        await writeFile(path.join(evidenceDir, 'backup-manifest.json'), JSON.stringify(originalManifest, null, 2));
        backend = startBackend(restored, 'restored-backend');
        await waitFor(`${apiUrl}/health`, [backend]);
        await verifyRestoredData();
        await writeFile(path.join(evidenceDir, 'restore-result.json'), JSON.stringify({
            elapsedMs: Date.now() - started, filesVerified: originalManifest.length,
            dataRollback: 'No loss relative to the stopped source database; not a production RPO commitment.'
        }, null, 2));
    });
    console.log(`Local verification passed. Evidence: ${directory}`);
} catch (error) {
    console.error(`Verification failed: ${error.message}\nService logs and results: ${directory}`);
    process.exitCode = 1;
} finally {
    await Promise.all([stop(frontend), stop(backend)]);
    streams.forEach(stream => stream.end());
    await writeFile(path.join(directory, 'verification.json'), JSON.stringify({
        finishedAt: new Date().toISOString(), backendSha256, results,
        scope: 'Local database, media, web UI, native unit logic, and simulated WeChat transport. Does not certify real WeChat delivery.'
    }, null, 2));
}
