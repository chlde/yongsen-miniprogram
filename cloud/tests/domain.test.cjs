'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { TrainingService } = require('../src/service.cjs');
const { MemoryRepository } = require('../src/repository.cjs');
const { digest } = require('../src/core.cjs');
const { runJobs } = require('../src/jobs.cjs');
async function setup() {
  const repo = new MemoryRepository(); let now = 1000000, command = 0;
  const service = new TrainingService({ repo, now: () => now, provider: { mediaUrl: async () => 'https://media.example.test/file', sendNotification: async () => {} } });
  const context = who => ({ OPENID: who, APPID: service.appId });
  const call = (who, action, data, key) => service.dispatch(context(who), action, data, key || `r-${++command}`);
  await call('admin', 'me'); await call('learner', 'me'); await call('stranger', 'me');
  await repo.put('roles', 'admin', { ...await repo.get('roles', 'admin'), role: 'ADMIN', sources: ['PLATFORM_ADMIN'] });
  const person = await call('admin', 'savePerson', { personNo: 'YS001', name: '测试学员' });
  const invite = await call('admin', 'invitePerson', { personId: person.id });
  await call('learner', 'bind', invite);
  await call('admin', 'decideBinding', { id: 'learner', decision: 'APPROVE' });
  await repo.put('materials', 'video', { title: '安全培训', status: 'READY', type: 'MP4', durationMs: 12000, fileId: 'cloud://fixture' });
  const task = await call('admin', 'createTask', { title: '安全任务', personIds: [person.id], materials: [{ materialId: 'video' }] });
  await call('admin', 'publishTask', { taskId: task.id });
  await runJobs.call(service);
  const assignment = (await repo.list('assignments'))[0];
  return { repo, service, call, task, person, assignment, advance: ms => { now += ms; } };
}
const video = (seq, elapsedMs, positionMs, extra = {}) => ({ seq, eventId: `event-${seq}`, type: 'VIDEO_SAMPLE', elapsedMs, payload: { positionMs, playing: true, playbackRate: 1, buffering: false, appVisible: true, pageVisible: true, playerVisible: true, ...extra } });
test('verified platform roles are unioned, default accounts cannot elevate or access other assignments', async () => {
  const { call, repo, assignment } = await setup();
  await assert.rejects(call('stranger', 'savePerson', { personNo: 'X', name: 'X' }), { code: 'FORBIDDEN' });
  await assert.rejects(call('stranger', 'assignmentDetail', { assignmentId: assignment._id }), { code: 'BINDING_REQUIRED' });
  const claim = await call('stranger', 'requestRoleCode');
  await assert.rejects(call('stranger', 'setRole', { code: claim.code, sources: ['PROJECT_MEMBER'], evidence: 'fake', platformName: 'fake' }), { code: 'FORBIDDEN' });
  await call('admin', 'setRole', { code: claim.code, sources: ['PLATFORM_ADMIN', 'PROJECT_MEMBER'], evidence: '管理员在微信平台核对', platformName: '项目成员' });
  assert.equal((await call('stranger', 'me')).role, 'ADMIN');
  await call('admin', 'revokeRole', { accountId: digest('stranger').slice(0, 16), source: 'PLATFORM_ADMIN' });
  assert.equal((await call('stranger', 'me')).role, 'ADMIN');
  await call('admin', 'revokeRole', { accountId: digest('stranger').slice(0, 16), source: 'PROJECT_MEMBER' });
  assert.equal((await call('stranger', 'me')).role, 'USER');
  assert.equal((await repo.get('roles', 'learner')).bindingStatus, 'ACTIVE');
});
test('task publishing is idempotent and stopped tasks cannot be learned', async () => {
  const { call, repo, task, assignment, service } = await setup();
  await call('admin', 'publishTask', { taskId: task.id }); await runJobs.call(service);
  assert.equal((await repo.list('assignments')).length, 1);
  await call('admin', 'cancelTask', { taskId: task.id });
  await assert.rejects(call('learner', 'startSession', { assignmentId: assignment._id, taskMaterialId: 'video' }), { code: 'TASK_NOT_ACTIVE' });
});
test('real-time video journey deduplicates batches, requires full progress and explicit confirmation', async () => {
  const { call, assignment, advance, repo } = await setup();
  const session = await call('learner', 'startSession', { assignmentId: assignment._id, taskMaterialId: 'video' });
  const ingest = (batchId, events) => call('learner', 'ingest', { sessionId: session.sessionId, batchId, events });
  await assert.rejects(call('learner', 'confirm', { assignmentId: assignment._id, accepted: true, confirmationTextVersion: 'mvp-1' }), { code: 'INCOMPLETE' });
  await ingest('baseline', [video(1, 0, 0)]);
  advance(5000);
  const batch = Array.from({ length: 5 }, (_, i) => video(i + 2, (i + 1) * 1000, (i + 1) * 1000));
  const first = await ingest('first', batch);
  assert.equal(first.confirmedProgress.effectiveMs, 5000);
  assert.equal((await ingest('first', batch)).confirmedProgress.effectiveMs, 5000);
  await assert.rejects(ingest('first', [video(2, 1000, 999)]), { code: 'BATCH_CONFLICT' });
  advance(5000);
  await ingest('second', Array.from({ length: 5 }, (_, i) => video(i + 7, (i + 6) * 1000, (i + 6) * 1000)));
  advance(2000);
  const end = await ingest('end', [video(12, 11000, 11000), video(13, 12000, 12000)]);
  assert.equal(end.confirmedProgress.requirementsMet, true);
  const fresh = await call('learner', 'assignmentDetail', { assignmentId: assignment._id });
  assert.equal(fresh.status, 'READY_TO_CONFIRM');
  await assert.rejects(call('learner', 'confirm', { assignmentId: assignment._id, accepted: true, confirmationTextVersion: 'mvp-1', progressDigest: 'old' }), { code: 'PROGRESS_CHANGED' });
  await call('learner', 'confirm', { assignmentId: assignment._id, accepted: true, confirmationTextVersion: 'mvp-1', progressDigest: fresh.progressDigest });
  assert.equal((await repo.get('assignments', assignment._id)).status, 'COMPLETED');
  assert.ok(await repo.get('completions', assignment._id));
});
test('seek, invisible playback, accelerated events, concurrent sessions and late retries cannot add credit', async () => {
  const { call, assignment, advance } = await setup();
  const request = { assignmentId: assignment._id, taskMaterialId: 'video' };
  const session = await call('learner', 'startSession', request);
  await assert.rejects(call('learner', 'startSession', request), { code: 'SESSION_IN_USE' });
  await call('learner', 'ingest', { sessionId: session.sessionId, batchId: 'base', events: [video(1, 0, 0)] });
  advance(1000);
  const result = await call('learner', 'ingest', { sessionId: session.sessionId, batchId: 'jump', events: [video(2, 1000, 10000)] });
  assert.equal(result.confirmedProgress.effectiveMs, 0);
  advance(1000);
  const hidden = await call('learner', 'ingest', { sessionId: session.sessionId, batchId: 'hidden', events: [video(3, 2000, 11000, { appVisible: false })] });
  assert.equal(hidden.confirmedProgress.effectiveMs, 0);
  await call('learner', 'takeoverSession', { ...request, expectedSessionId: session.sessionId });
  await assert.rejects(call('learner', 'ingest', { sessionId: session.sessionId, batchId: 'late', events: [video(4, 2000, 12000)] }), { code: 'SESSION_REPLACED' });
  const duplicate = await call('learner', 'ingest', { sessionId: session.sessionId, batchId: 'base', events: [video(1, 0, 0)] });
  assert.equal(duplicate.sessionValid, false);
});
test('failed fanout never activates an incomplete task', async () => {
  const { call, repo, service } = await setup();
  const person = await call('admin', 'savePerson', { personNo: 'UNBOUND', name: '尚未加入' });
  const task = await call('admin', 'createTask', { title: '待核对任务', personIds: [person.id], materials: [{ materialId: 'video' }] });
  await call('admin', 'publishTask', { taskId: task.id }); await runJobs.call(service);
  assert.equal((await repo.get('tasks', task.id)).status, 'PUBLISHING');
  assert.equal((await repo.get('jobs', `publish-${task.id}`)).state, 'FAILED');
  assert.equal((await repo.list('assignments', { taskId: task.id })).length, 0);
});
test('notification timeout is UNKNOWN and cannot be silently resent', async () => {
  const { call, repo, service, assignment } = await setup();
  await call('admin', 'saveNotificationSettings', { templateId: 'template', fields: { thing1: 'title' } });
  const request = await call('learner', 'prepareSubscription');
  await call('learner', 'subscriptionResult', { id: request.id, nonce: request.nonce, result: 'accept' });
  let sends = 0; service.provider.sendNotification = async () => { sends++; throw new Error('network timeout'); };
  await runJobs.call(service); await runJobs.call(service);
  assert.equal(sends, 1);
  assert.equal((await repo.get('notifications', `${assignment._id}-initial`)).state, 'UNKNOWN');
});

test('unsubscribed recipients do not starve later subscribed recipients', async () => {
  const { call, repo, service, assignment } = await setup();
  await call('admin', 'saveNotificationSettings', { templateId: 'template', fields: { thing1: 'title' } });
  for (let n = 0; n < 25; n++) await repo.put('notifications', `000-${String(n).padStart(2, '0')}`, { _openid: 'stranger', state: 'PENDING' });
  const request = await call('learner', 'prepareSubscription');
  await call('learner', 'subscriptionResult', { id: request.id, nonce: request.nonce, result: 'accept' });
  let sends = 0; service.provider.sendNotification = async () => { sends++; };
  await runJobs.call(service); await runJobs.call(service); await runJobs.call(service);
  assert.equal(sends, 1);
  assert.equal((await repo.get('notifications', `${assignment._id}-initial`)).state, 'ACCEPTED');
});

test('PDF time must cover every page; hidden reading and page switches add no credit', async () => {
  const math = require('../src/learning-math.cjs');
  const material = { type: 'PDF', pages: [{ pageNo: 1, height: 600 }, { pageNo: 2, height: 900 }], rule: { minPageVisibleMs: 30000 } };
  const progress = math.initial(material);
  const event = (pageNo, elapsedMs, top, bottom, visible = true) => ({ type: 'DOC_SAMPLE', elapsedMs, payload: { pageNo, rendered: true, appVisible: visible, pageVisible: visible, readerUnobscured: true, visibleTopPx: top, visibleBottomPx: bottom } });
  for (let n = 1; n <= 30; n++) math.apply(material, progress, event(1, (n - 1) * 1000, 0, 600), event(1, n * 1000, 0, 600), 1000);
  assert.equal(progress.pageProgress[0].requirementsMet, true); assert.equal(progress.requirementsMet, false);
  assert.equal(math.apply(material, progress, event(1, 30000, 0, 600), event(2, 31000, 0, 900), 1000).used, 0);
  assert.equal(math.apply(material, progress, event(2, 31000, 0, 900, false), event(2, 32000, 0, 900), 1000).used, 0);
  for (let n = 1; n <= 30; n++) math.apply(material, progress, event(2, (n - 1) * 1000, 0, 900), event(2, n * 1000, 0, 900), 1000);
  assert.equal(progress.requirementsMet, true);
});

test('late publication exposure cannot reset progress already confirmed by a learning session', async () => {
  const { repo, call, service, assignment, advance } = await setup();
  assert.equal((await repo.get('assignments', assignment._id)).visible, false);
  const session = await call('learner', 'startSession', { assignmentId: assignment._id, taskMaterialId: 'video' });
  await call('learner', 'ingest', { sessionId: session.sessionId, batchId: 'base', events: [video(1, 0, 0)] });
  advance(1000);
  await call('learner', 'ingest', { sessionId: session.sessionId, batchId: 'progress', events: [video(2, 1000, 1000)] });
  await runJobs.call(service);
  const exposed = await repo.get('assignments', assignment._id);
  assert.equal(exposed.visible, true); assert.equal(exposed.materials[0].progress.effectiveMs, 1000);
});

test('out-of-order samples persist but a missing sequence never grants offline learning time', async () => {
  const { call, assignment, advance } = await setup();
  const session = await call('learner', 'startSession', { assignmentId: assignment._id, taskMaterialId: 'video' });
  const ingest = (batchId, events) => call('learner', 'ingest', { sessionId: session.sessionId, batchId, events });
  await ingest('base', [video(1, 0, 0)]); advance(2000);
  const pending = await ingest('gap', [video(3, 2000, 2000)]);
  assert.equal(pending.verifiedSeq, 1); assert.equal(pending.confirmedProgress.effectiveMs, 0);
  advance(16000);
  const closed = await ingest('after-gap', [video(4, 18000, 3000)]);
  assert.equal(closed.verifiedSeq, 4); assert.equal(closed.confirmedProgress.effectiveMs, 0);
});
