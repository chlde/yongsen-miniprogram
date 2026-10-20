const test = require('node:test');
const assert = require('node:assert/strict');
const { SessionTracker, visiblePdfRange, visibleFraction } = require('../lib/session-tracker');
function rig(transport) {
  let now = 0, id = 0, stalled = 0;
  const reports = [], requests = [];
  const tracker = new SessionTracker({ clock: () => now, makeId: () => `id-${++id}`, setInterval: () => 1, clearInterval: () => {},
    sample: () => ({ type: 'VIDEO_SAMPLE', payload: { positionMs: now, playing: true, playbackRate: 1 } }),
    transport: async (sessionId, batch) => { requests.push(JSON.parse(JSON.stringify(batch))); if (transport) return transport(sessionId, batch); return { sessionValid: true, receivedSeq: batch.events.at(-1).seq, confirmedProgress: { effectiveMs: 0 } }; },
    onProgress: p => reports.push(p), onStall: () => stalled++, onError: () => {} });
  return { tracker, requests, reports, at: n => { now = n; }, stalled: () => stalled };
}
test('first sample is immediately acknowledged; display only server-confirmed progress', async () => {
  const r = rig(); await r.tracker.start('s1', 'VIDEO_SAMPLE', { positionMs: 0, playing: false });
  assert.equal(r.requests.length, 1); assert.equal(r.requests[0].events[0].elapsedMs, 0);
  r.at(1000); r.tracker.tick(); assert.equal(r.requests.length, 1);
  r.at(5000); r.tracker.tick(); await r.tracker.flush();
  assert.equal(r.reports.at(-1).effectiveMs, 0); assert.equal(r.requests[1].events[0].seq, 2);
  r.tracker.dispose();
});
test('an invalid baseline never starts the sampling timer or permits playback', async () => {
  const r = rig(async () => ({ sessionValid: false, sessionState: 'REPLACED', receivedSeq: 1 }));
  await assert.rejects(r.tracker.start('s1', 'VIDEO_SAMPLE', {}), /无法确认学习会话/);
  assert.equal(r.tracker.started, false); assert.equal(r.tracker.timer, undefined); assert.equal(r.stalled(), 1);
  r.tracker.dispose();
});
test('10 seconds without ACK stops sampling once and does not continue accumulating', async () => {
  let success = true;
  const r = rig(async (_, batch) => { if (!success) throw new Error('offline'); return { sessionValid: true, receivedSeq: batch.events.at(-1).seq }; });
  await r.tracker.start('s1', 'VIDEO_SAMPLE', {}); success = false;
  for (let t = 1000; t <= 10000; t += 1000) { r.at(t); r.tracker.tick(); await Promise.resolve(); }
  assert.equal(r.stalled(), 1); const seq = r.tracker.nextSeq;
  r.at(11000); r.tracker.tick(); assert.equal(r.tracker.nextSeq, seq); r.tracker.dispose();
});
test('retry keeps exact batch identity and immutable event payload', async () => {
  let fail = false;
  const r = rig(async (_, batch) => { if (fail) throw new Error('offline'); return { sessionValid: true, receivedSeq: batch.events.at(-1).seq }; });
  await r.tracker.start('s1', 'VIDEO_SAMPLE', {}); fail = true;
  const payload = { positionMs: 1000 }; r.at(1000); r.tracker.enqueue('VIDEO_SAMPLE', payload); payload.positionMs = 99999;
  await assert.rejects(r.tracker.flush()); fail = false; await r.tracker.flush();
  assert.deepEqual(r.requests[1], r.requests[2]); assert.equal(r.requests[1].events[0].payload.positionMs, 1000);
  r.tracker.dispose();
});
test('concurrent state changes preserve unsent events while single-flight request runs', async () => {
  const r = rig(); await r.tracker.start('s1', 'VIDEO_SAMPLE', {});
  let finish; r.tracker.transport = (_, batch) => new Promise(resolve => { finish = () => resolve({ sessionValid: true, receivedSeq: batch.events.at(-1).seq }); });
  r.at(1000); r.tracker.enqueue('VIDEO_SAMPLE', {}); const call = r.tracker.flush();
  r.at(1100); r.tracker.enqueue('HIDE', { appVisible: false }); finish(); await call;
  assert.equal(r.tracker.queue.length, 1); assert.equal(r.tracker.queue[0].type, 'HIDE'); r.tracker.dispose();
});
test('hidden page emits no periodic samples and replaced session stops', async () => {
  const r = rig(); await r.tracker.start('s1', 'VIDEO_SAMPLE', {}); r.tracker.setVisible(false); r.at(1000); r.tracker.tick();
  assert.equal(r.tracker.queue.length, 0);
  r.tracker.transport = async () => ({ sessionValid: false, sessionState: 'REPLACED' });
  await r.tracker.emit('HIDE', {}); assert.equal(r.stalled(), 1); r.tracker.dispose();
});
test('clock rollback stops instead of inventing elapsed time', async () => {
  const r = rig(); await r.tracker.start('s1', 'VIDEO_SAMPLE', {}); r.at(1000); r.tracker.tick(); r.at(500); r.tracker.tick();
  assert.equal(r.stalled(), 1); assert.equal(r.tracker.queue.length, 1); r.tracker.dispose();
});
test('PDF viewport converts to original pixels and excludes offscreen areas', () => {
  assert.deepEqual(visiblePdfRange({ imageTop: -100, imageHeight: 800, viewportTop: 0, viewportHeight: 400, sourceHeight: 1600, screenHeight: 1000 }), { visibleTopPx: 200, visibleBottomPx: 1000 });
  assert.equal(visiblePdfRange({ imageTop: 500, imageHeight: 800, viewportTop: 0, viewportHeight: 400, sourceHeight: 1600, screenHeight: 1000 }), null);
  assert.deepEqual(visiblePdfRange({ imageTop: 10, imageHeight: 800, viewportTop: 0, viewportHeight: 900, sourceHeight: 1600, screenHeight: 1000 }), { visibleTopPx: 0, visibleBottomPx: 1600 });
});
test('PDF clips the reader against the physical screen and rejects an entirely offscreen reader', () => {
  assert.deepEqual(visiblePdfRange({ imageTop: 500, imageHeight: 800, viewportTop: 500, viewportHeight: 600, sourceHeight: 1600, screenHeight: 700 }), { visibleTopPx: 0, visibleBottomPx: 400 });
  assert.equal(visiblePdfRange({ imageTop: 900, imageHeight: 800, viewportTop: 900, viewportHeight: 600, sourceHeight: 1600, screenHeight: 700 }), null);
  assert.equal(visiblePdfRange({ imageTop: 0, imageHeight: 800, viewportTop: 0, viewportHeight: 600, sourceHeight: 1600 }), null);
});
test('video visibility includes horizontal and vertical screen clipping', () => {
  const screen = { left: 0, top: 0, width: 400, height: 700 };
  assert.equal(visibleFraction({ left: 0, top: 600, width: 400, height: 200 }, screen), 0.5);
  assert.equal(visibleFraction({ left: 200, top: 600, width: 400, height: 200 }, screen), 0.25);
  assert.equal(visibleFraction({ left: 0, top: 700, width: 400, height: 200 }, screen), 0);
});
test('finish stops samples and drains CLOSE after an already in-flight batch', async () => {
  const r = rig(); await r.tracker.start('s1', 'VIDEO_SAMPLE', {});
  let resolvePending, sends = 0;
  r.tracker.transport = (_, batch) => {
    sends++;
    const result = { sessionValid: true, receivedSeq: batch.events.at(-1).seq };
    return sends === 1 ? new Promise(resolve => { resolvePending = () => resolve(result); }) : Promise.resolve(result);
  };
  r.at(1000); r.tracker.enqueue('VIDEO_SAMPLE', {}); const inFlight = r.tracker.flush();
  const closing = r.tracker.finish({ playing: false });
  const seq = r.tracker.nextSeq; r.at(2000); r.tracker.tick(); assert.equal(r.tracker.nextSeq, seq);
  resolvePending(); await inFlight; await closing;
  assert.equal(sends, 2); assert.equal(r.tracker.queue.length, 0); assert.equal(r.tracker.disposed, true);
});
test('native WXML conditions use data binding instead of always-truthy literal strings', () => {
  const fs = require('node:fs'), path = require('node:path');
  for (const name of ['login', 'tasks', 'task', 'learn', 'records']) {
    const text = fs.readFileSync(path.join(__dirname, '..', 'pages', name, 'index.wxml'), 'utf8');
    for (const match of text.matchAll(/wx:(?:if|elif|for)="([^"]*)"/g)) assert.ok(match[1].startsWith('{{') && match[1].endsWith('}}'), `${name}: ${match[0]}`);
  }
});
