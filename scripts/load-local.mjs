import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { root, run } from './process.mjs';

// Real wall-clock load against an isolated local backend. Never send real WeChat messages.
const base = process.env.LOAD_API_URL || 'http://127.0.0.1:18081';
const address = new URL(base);
assert(['127.0.0.1', 'localhost'].includes(address.hostname), 'Load tests require a loopback endpoint');
const users = Number(process.env.LOAD_USERS || 100);
const durationSeconds = Number(process.env.LOAD_SECONDS || 1800);
assert(users > 0 && users <= 100 && durationSeconds >= 30 && durationSeconds <= 1800);
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const output = path.resolve(process.env.LOAD_EVIDENCE_DIR || path.join(root, '.local', 'load', stamp));
await mkdir(output, { recursive: true });
const latencies = [];
const failures = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let requests = 0;
let admin;

async function call(route, method = 'GET', body, token = admin, key) {
    const started = performance.now();
    const response = await fetch(`${base}/api/v1${route}`, {
        method, headers: { 'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
            ...(key ? { 'Idempotency-Key': key } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(15000)
    });
    const raw = await response.text();
    requests++;
    latencies.push(performance.now() - started);
    assert(response.ok, `${method} ${route}: ${response.status} ${raw.slice(0, 300)}`);
    return response.headers.get('content-type')?.includes('application/json') ? JSON.parse(raw).data : raw;
}

admin = (await call('/admin/auth/login', 'POST', { username: 'admin', password: process.env.E2E_ADMIN_PASSWORD || 'LocalTrain123!' }, '')).accessToken;
assert.equal((await call('/me')).providerMode, 'mock', 'Load test refuses a real WeChat backend');
const fixture = path.join(output, 'load-video.mp4');
await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=0x166256:s=320x180:r=1',
    '-t', String(durationSeconds + 30), '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', fixture]);
const bytes = await readFile(fixture);
const upload = await call('/admin/materials/uploads', 'POST', { title: `本地容量验证 ${stamp}`, type: 'MP4', size: bytes.length, filename: 'load-video.mp4' });
assert((await fetch(new URL(upload.uploadUrl, base), { method: 'PUT', body: bytes })).ok);
await call(`/admin/materials/${upload.id}/upload-complete`, 'POST', {});
let material;
for (let attempts = 0; attempts < 60; attempts++) {
    material = await call(`/admin/materials/${upload.id}`);
    if (material.status === 'READY') break;
    assert.notEqual(material.status, 'REJECTED');
    await sleep(1000);
}
assert.equal(material.status, 'READY');
await call(`/admin/materials/${upload.id}/compatibility-check`, 'POST', { passed: true });
const learners = [];
for (let i = 0; i < users; i++) {
    const key = `load-${stamp}-${i}`;
    const person = await call('/admin/people', 'POST', { personNo: key, name: `容量验证${i + 1}`, unit: '本地隔离测试', project: stamp });
    const invitation = await call(`/admin/people/${person.id}/invitation`, 'POST', {});
    const token = (await call('/auth/wechat-login', 'POST', { code: `mock:${key}` }, '')).accessToken;
    const binding = await call('/me/binding-requests', 'POST', { inviteCode: invitation.inviteCode }, token);
    await call(`/admin/bindings/${binding.id}/decision`, 'POST', { decision: 'APPROVE', reason: '本地隔离容量测试' });
    learners.push({ person, token });
}
const task = await call('/admin/tasks', 'POST', { title: `100人容量验证 ${stamp}`, description: '测试数据，不发送真实微信',
    dueAt: new Date(Date.now() + 86400000).toISOString(), personIds: learners.map(row => row.person.id),
    materials: [{ materialId: upload.id, rule: { playbackRate: 1 } }] });
await call(`/admin/tasks/${task.id}/publish`, 'POST', {}, admin, randomUUID());
await Promise.all(learners.map(async learner => {
    learner.assignment = (await call('/me/assignments', 'GET', undefined, learner.token)).items.find(row => row.taskId === task.id);
    const detail = await call(`/me/assignments/${learner.assignment.id}`, 'GET', undefined, learner.token);
    learner.session = await call('/learning/sessions', 'POST', { assignmentId: learner.assignment.id,
        taskMaterialId: detail.materials[0].taskMaterialId }, learner.token);
}));
const workloadStarted = performance.now();
console.log(`Starting ${users} learners for ${durationSeconds}s; evidence ${output}`);
let stopQueries = false;
const queries = (async () => {
    let round = 0;
    while (!stopQueries) {
        await sleep(1000);
        round++;
        if (round % 30 !== 0 || stopQueries) continue;
        try {
            const page = await call(`/admin/tasks/${task.id}/assignments`);
            assert(page.items.length > 0);
            if (round % 60 === 0) {
                const csv = await call(`/admin/tasks/${task.id}/records.csv`);
                assert(csv.includes(learners[0].person.personNo));
                console.log(`Load progress: ${round}s, ${requests} requests, ${failures.length} failures`);
            }
        } catch (error) { failures.push({ operation: 'query/export', message: error.message }); }
    }
})();
const sample = (seq, elapsedMs) => ({ eventId: randomUUID(), seq, type: 'VIDEO_SAMPLE', elapsedMs,
    payload: { positionMs: elapsedMs, playing: true, playbackRate: 1, buffering: false,
        appVisible: true, pageVisible: true, playerVisible: true } });
await Promise.all(learners.map(async (learner, index) => {
    try {
        let seq = 1;
        const start = performance.now();
        const route = `/learning/sessions/${learner.session.sessionId}/events:batch`;
        await call(route, 'POST', { batchId: randomUUID(), events: [sample(seq++, 0)] }, learner.token);
        let pending = [];
        for (let tick = 1; tick <= durationSeconds; tick++) {
            await sleep(Math.max(0, start + tick * 1000 - performance.now()));
            // Ten learners have a 20-second network outage, but cannot submit invented offline progress.
            if (index < 10 && tick >= 600 && tick < 620) continue;
            pending.push(sample(seq++, Math.round(performance.now() - start)));
            if (tick % 5 !== 0 && tick !== durationSeconds) continue;
            const batch = { batchId: randomUUID(), events: pending };
            const result = await call(route, 'POST', batch, learner.token);
            assert.equal(result.sessionValid, true);
            assert.equal(result.confirmedProgress.requirementsMet, false, 'Longer video must not complete early');
            assert(result.confirmedProgress.effectiveMs <= performance.now() - start + 1000, 'Effective time exceeds wall clock');
            if (index < 20 && tick % 60 === 0) {
                const retried = await call(route, 'POST', batch, learner.token);
                assert.equal(retried.confirmedProgress.effectiveMs, result.confirmedProgress.effectiveMs, 'Replay counted twice');
            }
            pending = [];
        }
        const final = await call(`/me/assignments/${learner.assignment.id}/progress`, 'GET', undefined, learner.token);
        learner.effectiveMs = final.materials[0].progress.effectiveMs;
        assert(learner.effectiveMs >= (durationSeconds - 40) * 1000, 'Unexpected progress loss');
        assert.equal(final.requirementsMet, false);
        await call(`/learning/sessions/${learner.session.sessionId}/close`, 'POST', {}, learner.token);
    } catch (error) {
        failures.push({ learner: index, message: error.message });
    }
}));
stopQueries = true;
await queries;
const sorted = latencies.toSorted((a, b) => a - b);
const report = { startedAt: stamp, finishedAt: new Date().toISOString(), users, durationSeconds,
    actualDurationMs: performance.now() - workloadStarted, requests, failures,
    latencyMs: { median: sorted[Math.floor(sorted.length * .5)], p95: sorted[Math.floor(sorted.length * .95)], max: sorted.at(-1) },
    effectiveMs: learners.map(row => row.effectiveMs ?? null), scope: 'Local API ingestion and persistence; simulated identities; excludes video streaming bandwidth and real WeChat.' };
await writeFile(path.join(output, 'load-report.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ ...report, effectiveMs: undefined }, null, 2));
assert.equal(failures.length, 0, 'Load validation failed');
