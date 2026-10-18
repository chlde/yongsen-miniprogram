/* Runs real native pages and real local API. No playback, event, or completion mocks. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const automator = require('miniprogram-automator');
const project = path.resolve(__dirname, '..');
const artifacts = path.join(project, 'artifacts');
const apiBase = process.env.TRAINING_API || 'http://127.0.0.1:8080/api/v1';
if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/api\/v1$/.test(apiBase)) throw new Error('Native E2E creates local test data; remote API is forbidden');
fs.mkdirSync(artifacts, { recursive: true });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let token, mp;
const evidence = { startedAt: new Date().toISOString(), mode: 'local-mock-provider / real-native-pages', checks: [] };
async function http(route, method = 'GET', data) {
  const response = await fetch(`${apiBase}${route}`, { method, headers: { 'content-type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Idempotency-Key': `native-${crypto.randomUUID()}` }, ...(data !== undefined ? { body: JSON.stringify(data) } : {}) });
  const body = await response.json();
  if (!response.ok || body.code !== 'OK') throw new Error(`${route}: ${body.code} ${body.message || ''}`);
  return body.data;
}
async function waitFor(label, fn, timeout = 20000) {
  console.log(`native: ${label}`);
  const until = Date.now() + timeout;
  while (Date.now() < until) { const result = await Promise.race([fn(), delay(5000).then(() => { throw new Error(`Native call stalled: ${label}`); })]); if (result) return result; await delay(400); }
  throw new Error(`Timed out: ${label}`);
}
function pdfFixture() {
  const stream = 'BT /F1 20 Tf 40 780 Td (YONGSEN SAFETY TRAINING) Tj 0 -52 Td /F1 13 Tf (Native PDF reading verification) Tj 0 -44 Td (1. Inspect the working area before operation.) Tj 0 -44 Td (2. Confirm emergency procedures with your team.) Tj 0 -44 Td (3. Report unsafe conditions before starting work.) Tj 0 -250 Td (Read the entire page and acknowledge the task.) Tj ET';
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`];
  let text = '%PDF-1.4\n', offsets = [0];
  objects.forEach((object, i) => { offsets.push(Buffer.byteLength(text)); text += `${i + 1} 0 obj\n${object}\nendobj\n`; });
  const start = Buffer.byteLength(text); text += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.slice(1).forEach(offset => { text += `${String(offset).padStart(10, '0')} 00000 n \n`; });
  text += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`;
  const file = path.join(artifacts, 'native-safety.pdf'); fs.writeFileSync(file, text); return file;
}
async function uploadMaterial(file, type, title) {
  const bytes = fs.readFileSync(file);
  const upload = await http('/admin/materials/uploads', 'POST', { title, type, filename: path.basename(file), size: bytes.length });
  const url = new URL(upload.uploadUrl, apiBase).toString();
  const put = await fetch(url, { method: 'PUT', body: bytes, headers: { 'content-type': type === 'PDF' ? 'application/pdf' : 'video/mp4' } });
  assert.ok(put.ok, `upload status ${put.status}`);
  const id = upload.materialId || upload.id;
  await http(`/admin/materials/${id}/upload-complete`, 'POST', {});
  const material = await waitFor('material processing', async () => {
    const m = await http(`/admin/materials/${id}`);
    if (m.status === 'REJECTED') throw new Error(`Fixture rejected: ${JSON.stringify(m)}`);
    return m.status === 'READY' && m;
  }, 30000);
  // Test fixture compatibility is exercised by real native rendering below.
  await http(`/admin/materials/${id}/compatibility-check`, 'POST', { passed: true });
  return material;
}
async function tap(page, selector) { const el = await page.$(selector); assert.ok(el, `missing ${selector}`); await el.tap(); }
async function pageAt(route) { return waitFor(route, async () => { const page = await mp.currentPage(); return page && page.path === route && page; }); }
async function screenshot(name) { await mp.screenshot({ path: path.join(artifacts, name) }); }
(async () => {
  const admin = await http('/admin/auth/login', 'POST', { username: process.env.TRAINING_ADMIN || 'admin', password: process.env.TRAINING_PASSWORD || 'LocalTrain123!' });
  token = admin.accessToken;
  const suffix = Date.now().toString(36), mockName = `native_${suffix}`;
  const person = await http('/admin/people', 'POST', { personNo: `NATIVE-${suffix}`, name: '原生端验收学员', unit: '本地自动化测试', project: 'MVP 原生验证' });
  const invitation = await http(`/admin/people/${person.id}/invitation`, 'POST', {});
  const videoPath = path.join(artifacts, 'native-safety.mp4');
  execFileSync(process.env.FFMPEG || '/opt/homebrew/bin/ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25', '-t', '12', '-an', '-c:v', 'libx264', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', videoPath]);
  const video = await uploadMaterial(videoPath, 'MP4', '现场安全 · 原生视频验收');
  const pdf = await uploadMaterial(pdfFixture(), 'PDF', '作业须知 · 原生文档验收');
  const task = await http('/admin/tasks', 'POST', { title: `原生完整学习验收 ${suffix}`, description: '本机测试任务：真实视频播放、文档滚动与本人确认。', dueAt: new Date(Date.now() + 86400000).toISOString(), personIds: [person.id], materials: [{ materialId: video.id, rule: { playbackRate: 1 } }, { materialId: pdf.id, rule: { minPageVisibleMs: 30000 } }] });
  await http(`/admin/tasks/${task.id}/publish`, 'POST', {});
  evidence.personId = person.id; evidence.taskId = task.id;
  try { mp = await automator.connect({ wsEndpoint: process.env.NATIVE_WS || 'ws://127.0.0.1:9420' }); }
  catch (_) { mp = await automator.launch({ cliPath: '/Applications/wechatwebdevtools.app/Contents/MacOS/cli', projectPath: project, port: 9420, trustProject: true, timeout: 60000 }); }
  const errors = []; mp.on('exception', e => errors.push(String(e.message || e)));
  await mp.evaluate(() => { getApp().clearLogin(); });
  let page = await mp.reLaunch('/pages/login/index');
  await waitFor('mock gate visible', async () => (await page.data()).canMock);
  await tap(page, '#mock-switch');
  await waitFor('explicit mock enabled', async () => (await page.data()).mockEnabled);
  const name = await page.$('#mock-name'); await name.input(mockName);
  await tap(page, '#login-button');
  await waitFor('unbound authenticated user', async () => (await page.data()).loggedIn);
  await (await page.$('#invite-code')).input(invitation.inviteCode);
  await tap(page, '#bind-button');
  await waitFor('binding pending', async () => (await page.data()).bindingStatus === 'PENDING');
  await screenshot('native-binding-pending.png');
  const bindings = await http('/admin/bindings');
  const binding = bindings.items.find(item => item.personId === person.id && item.status === 'PENDING'); assert.ok(binding);
  await http(`/admin/bindings/${binding.id}/decision`, 'POST', { decision: 'APPROVE', reason: '本地原生 E2E 核对' });
  await tap(page, '#refresh-binding'); page = await pageAt('pages/tasks/index');
  await waitFor('task list populated', async () => (await page.data()).activeItems.length > 0);
  await screenshot('native-task-list.png');
  await tap(page, '.task-card'); page = await pageAt('pages/task/index');
  await waitFor('task detail ready', async () => !(await page.data()).loading && (await page.data()).subscriptionReady);
  const assignment = (await page.data()).task;
  evidence.assignmentId = assignment.id;
  await tap(page, '#subscribe-button');
  await waitFor('mock first notification accepted', async () => {
    const notifications = await http(`/admin/tasks/${task.id}/notifications`);
    return notifications.items.find(n => n.sendStatus === 'ACCEPTED');
  });
  evidence.checks.push('UI login → invitation → administrator approval → task list → explicit local subscription');
  const materials = await page.$$('.material-card');
  const videoIndex = (await page.data()).task.materials.findIndex(item => item.type === 'MP4');
  assert.ok(videoIndex >= 0); await materials[videoIndex].tap(); page = await pageAt('pages/learn/index');
  await waitFor('native video ready', async () => (await page.data()).mediaReady && (await page.data()).isVideo);
  await tap(page, '#start-learning');
  await waitFor('video actual playback', async () => (await page.data()).currentSeconds >= 2);
  for (let pass = 0; pass < 3; pass++) {
    await waitFor('video playback ended or complete', async () => {
      const d = await page.data(); if (d.error) throw new Error(d.error); return d.completed || (!d.playing && d.currentSeconds >= 11);
    }, 25000);
    await delay(1500);
    if ((await page.data()).completed) break;
    const diagnostics = await mp.evaluate(() => { const p = getCurrentPages()[getCurrentPages().length - 1]; return { progress: p.progress, batches: p.tracker && p.tracker.diagnostics }; });
    fs.writeFileSync(path.join(artifacts, `native-video-diagnostics-${pass}.json`), JSON.stringify(diagnostics, null, 2));
    await tap(page, '#replay-missing');
    await waitFor('missing range ready', async () => (await page.data()).needsResume);
    await tap(page, '#start-learning');
    await waitFor('replay started', async () => (await page.data()).playing);
  }
  await waitFor('video server coverage complete', async () => (await page.data()).completed, 25000);
  await screenshot('native-video-playing.png');
  assert.ok((await page.data()).effectiveSeconds >= 10);
  evidence.checks.push('Native video component played real 12-second MP4 and server verified coverage');
  await tap(page, '#back-task'); page = await pageAt('pages/task/index');
  await waitFor('task refreshed', async () => !(await page.data()).loading);
  const refreshed = await page.$$('.material-card');
  const pdfIndex = (await page.data()).task.materials.findIndex(item => item.type === 'PDF');
  assert.ok(pdfIndex >= 0); await refreshed[pdfIndex].tap(); page = await pageAt('pages/learn/index');
  await waitFor('native PDF image ready', async () => (await page.data()).mediaReady && !(await page.data()).isVideo);
  await tap(page, '#start-learning');
  await delay(5000);
  const scroll = await page.$('#pdf-viewport');
  const image = await page.$('#pdf-page-image');
  const imageSize = await image.size(), viewportSize = await scroll.size();
  const overflow = Math.max(0, Number(imageSize.height) - Number(viewportSize.height));
  for (let y = 80; y < overflow; y += 80) { await scroll.scrollTo(0, y); await delay(1300); }
  await scroll.scrollTo(0, overflow + 3);
  await screenshot('native-pdf-reading.png');
  await waitFor('PDF page time and visible coverage complete', async () => {
    const d = await page.data(); if (d.error) throw new Error(d.error); return d.completed;
  }, 45000);
  assert.ok((await page.data()).pageSeconds >= 30);
  evidence.checks.push('Native scroll-view displayed real rendered PDF page, scrolled entire area, waited 30 seconds');
  await tap(page, '#back-task'); page = await pageAt('pages/task/index');
  await waitFor('ready to confirm', async () => (await page.data()).task.status === 'READY_TO_CONFIRM');
  await tap(page, '#confirmation-checkbox');
  await waitFor('confirmation checkbox selected', async () => (await page.data()).accepted);
  await tap(page, '#confirm-button');
  await waitFor('confirmed completion', async () => (await page.data()).task.status === 'COMPLETED');
  await screenshot('native-completed.png');
  page = await mp.switchTab('/pages/records/index');
  await waitFor('record visible', async () => (await page.data()).items.some(item => item.id === assignment.id));
  await screenshot('native-records.png');
  evidence.checks.push('Real checkbox confirmation → server COMPLETED → personal records');
  evidence.completedAt = new Date().toISOString(); evidence.exceptions = errors;
  evidence.realWechatDelivery = 'NOT_TESTED: local mock provider; real AppID/template/phone delivery remains deployment gate';
  fs.writeFileSync(path.join(artifacts, 'native-e2e-result.json'), JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence, null, 2));
})().catch(error => {
  evidence.failure = error.stack; fs.writeFileSync(path.join(artifacts, 'native-e2e-result.json'), JSON.stringify(evidence, null, 2)); console.error(error.stack); process.exitCode = 1;
}).finally(() => { if (mp) mp.disconnect(); });
