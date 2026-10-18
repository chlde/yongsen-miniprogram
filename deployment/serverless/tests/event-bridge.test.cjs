const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { randomBytes } = require('node:crypto');
const { createHandler, runtimeConfig, requestWorker } = require('../event-bridge/index.js');

const env = { TRAINING_WORKER_BASE_URL: 'https://training.company.cn', TRAINING_WORKER_SECRET: randomBytes(32).toString('base64url') };
const event = { jobId: 'a'.repeat(64) };

function transport(run) {
  return (url, options, callback) => {
    const request = new EventEmitter();
    request.destroyed = false;
    request.destroy = () => { request.destroyed = true; };
    request.end = body => queueMicrotask(() => run({ url, options, callback, request, body }));
    return request;
  };
}
function incoming(callback, status = 'done', { statusCode = 200, text = JSON.stringify({ status }), headers = {} } = {}) {
  const response = new EventEmitter();
  response.statusCode = statusCode; response.headers = headers;
  response.destroyed = false; response.destroy = () => { response.destroyed = true; };
  callback(response);
  if (!response.destroyed) { response.emit('data', Buffer.from(text)); response.emit('end'); }
  return response;
}
const answer = status => transport(({ callback }) => incoming(callback, status));

test('bridge awaits a completed body and sends only a fixed path and worker credential', async () => {
  let completed = false;
  const handler = createHandler({ env, requestImpl: transport(({ url, options, callback, body }) => {
    assert.equal(url, `https://training.company.cn/internal/background-work/${event.jobId}/run`);
    assert.equal(options.method, 'POST'); assert.equal(options.agent, false); assert.equal(body, '{}');
    assert.equal(options.headers.authorization, `Bearer ${env.TRAINING_WORKER_SECRET}`);
    assert.equal(options.headers['content-length'], '2');
    completed = true; incoming(callback);
  }) });
  assert.deepEqual(await handler(event), { ...event, status: 'done' }); assert.equal(completed, true);
});

test('unfinished results throw while a terminal failure is acknowledged without repeating the business action', async () => {
  for (const status of ['busy', 'retry']) await assert.rejects(createHandler({ env, requestImpl: answer(status) })(event), /retry/);
  assert.deepEqual(await createHandler({ env, requestImpl: answer('failed') })(event), { ...event, status: 'failed' });
});

test('untrusted event cannot supply a URL, a secret, an unexpected field or an invalid job identifier', async () => {
  for (const invalid of [null, [], {}, { jobId: '../admin' }, { jobId: 'A'.repeat(64) }, { ...event, url: 'https://attacker.cn' }, { ...event, secret: 'hidden' }]) {
    let called = false;
    await assert.rejects(createHandler({ env, requestImpl: transport(({ callback }) => { called = true; incoming(callback); }) })(invalid), /Invalid/);
    assert.equal(called, false);
  }
});

test('invalid runtime origins and weak or malformed worker secrets fail before network access', () => {
  for (const origin of ['http://training.company.cn', 'https://127.0.0.1', 'https://localhost', 'https://example.com', 'https://training.company.cn/path', 'https://name:secret@training.company.cn', 'https://training.company.cn:8443', 'https://training.company.cn?token=hidden']) {
    assert.throws(() => runtimeConfig({ ...env, TRAINING_WORKER_BASE_URL: origin }), /HTTPS|Worker/);
  }
  for (const secret of ['', 'short', '\n'.repeat(44), 'a'.repeat(129)]) assert.throws(() => runtimeConfig({ ...env, TRAINING_WORKER_SECRET: secret }), /authentication/);
});

test('HTTP errors, redirect, oversized payloads and transport messages are sanitized and retryable', async () => {
  const requests = [
    transport(({ callback }) => incoming(callback, '', { statusCode: 503, text: 'private-content' })),
    transport(({ callback }) => incoming(callback, '', { statusCode: 302, headers: { location: 'https://attacker.cn' } })),
    transport(({ callback }) => incoming(callback, '', { headers: { 'content-length': '9000' } })),
    transport(({ callback }) => incoming(callback, '', { text: 'x'.repeat(9000) })),
    transport(({ callback }) => incoming(callback, '', { text: 'not json' })), answer('unexpected'),
    transport(({ request }) => request.emit('error', new Error(`sensitive ${env.TRAINING_WORKER_SECRET}`)))
  ];
  for (const requestImpl of requests) await assert.rejects(createHandler({ env, requestImpl })(event), error => {
    assert.match(error.message, /retry/); assert.equal(error.message.includes(env.TRAINING_WORKER_SECRET), false);
    assert.equal(error.message.includes('private-content'), false); return true;
  });
});

test('bridge refuses a near-expired invocation instead of returning before work completes', async () => {
  let called = false;
  await assert.rejects(createHandler({ env, requestImpl: transport(({ callback }) => { called = true; incoming(callback); }) })(event,
    { getRemainingTimeInMillis: () => 10000 }), /Insufficient/);
  assert.equal(called, false);
});

test('a throwing SCF context helper does not prevent a durable job from running', async () => {
  const handler = createHandler({ env, requestImpl: answer('done') });
  assert.deepEqual(await handler(event, {
    getRemainingTimeInMillis() { throw new TypeError('client.ms_elapsed is not a function'); }
  }), { ...event, status: 'done' });
  for (const remaining of [NaN, Infinity, -1]) {
    await assert.rejects(handler(event, { getRemainingTimeInMillis: () => remaining }), /Insufficient/);
  }
});

test('one total deadline destroys a request still waiting for headers without relying on socket activity', async () => {
  let started;
  await assert.rejects(requestWorker('https://training.company.cn/internal', env.TRAINING_WORKER_SECRET, 20,
    transport(({ request }) => { started = request; })), /not confirmed/);
  assert.equal(started.destroyed, true);
});

test('one total deadline also bounds an unfinished response after headers arrive', async () => {
  let response;
  await assert.rejects(requestWorker('https://training.company.cn/internal', env.TRAINING_WORKER_SECRET, 20,
    transport(({ callback }) => {
      response = new EventEmitter(); response.statusCode = 200; response.headers = {};
      response.destroyed = false; response.destroy = () => { response.destroyed = true; };
      callback(response); response.emit('data', Buffer.from('{"status":'));
    })), /not confirmed/);
  assert.equal(response.destroyed, true);
});
