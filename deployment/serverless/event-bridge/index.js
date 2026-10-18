'use strict';

const https = require('node:https');

const MAX_RESPONSE_BYTES = 8192;
const WORKER_TIMEOUT_MS = 840000;
const SHUTDOWN_MARGIN_MS = 15000;

function runtimeConfig(env) {
  let origin;
  try { origin = new URL(env.TRAINING_WORKER_BASE_URL); }
  catch (_) { throw new Error('Worker HTTPS origin is not configured.'); }
  if (origin.protocol !== 'https:' || origin.username || origin.password || origin.port ||
      origin.search || origin.hash || origin.pathname !== '/' ||
      !/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}$/i.test(origin.hostname) ||
      /(^|[.-])(localhost|local|internal|intranet|example|invalid|test|mock)([.-]|$)/i.test(origin.hostname)) {
    throw new Error('Worker must use a configured HTTPS origin without path or credentials.');
  }
  if (!/^[A-Za-z0-9_-]{43,128}$/.test(env.TRAINING_WORKER_SECRET || '')) {
    throw new Error('Worker authentication requires a random base64url secret of at least 32 bytes.');
  }
  return { origin: origin.origin, secret: env.TRAINING_WORKER_SECRET };
}

function requestWorker(url, secret, timeout, requestImpl = https.request) {
  return new Promise((resolve, reject) => {
    let request;
    let response;
    let finished = false;
    const finish = (error, result) => {
      if (finished) return;
      finished = true;
      clearTimeout(deadline);
      if (error) {
        response?.destroy();
        request?.destroy();
        reject(new Error('Worker HTTP request was not confirmed.'));
      } else resolve(result);
    };
    // This covers DNS, connection, waiting for headers, and the whole response.
    // Native https avoids fetch/Undici's separate default 300-second header limit.
    const deadline = setTimeout(() => finish(true), timeout);
    try {
      request = requestImpl(url, {
        method: 'POST', agent: false,
        headers: { authorization: `Bearer ${secret}`, 'content-type': 'application/json', 'content-length': '2' }
      }, incoming => {
        response = incoming;
        if (finished) { incoming.destroy(); return; }
        if (incoming.statusCode < 200 || incoming.statusCode >= 300 ||
            Number(incoming.headers['content-length']) > MAX_RESPONSE_BYTES) {
          // https.request never follows redirects; reject all 3xx without a retry to Location.
          finish(true); return;
        }
        let bytes = 0;
        const chunks = [];
        incoming.on('data', chunk => {
          if (finished) return;
          bytes += chunk.length;
          if (bytes > MAX_RESPONSE_BYTES) { finish(true); return; }
          chunks.push(chunk);
        });
        incoming.on('end', () => {
          if (finished) return;
          try { finish(false, JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
          catch (_) { finish(true); }
        });
        incoming.on('error', () => finish(true));
        incoming.on('aborted', () => finish(true));
      });
      request.on('error', () => finish(true));
      request.end('{}');
    } catch (_) { finish(true); }
  });
}

function createHandler({ env = process.env, requestImpl = https.request } = {}) {
  return async function mainHandler(event, context = {}) {
    if (!event || typeof event !== 'object' || Array.isArray(event) ||
        Object.keys(event).length !== 1 || !/^[a-f0-9]{64}$/.test(event.jobId || '')) {
      throw new Error('Invalid background-work event; expected only a persisted jobId.');
    }
    const config = runtimeConfig(env);
    let remaining = WORKER_TIMEOUT_MS + SHUTDOWN_MARGIN_MS;
    if (typeof context.getRemainingTimeInMillis === 'function') {
      try { remaining = context.getRemainingTimeInMillis(); }
      catch (_) {
        // SCF Node.js 20.19 can throw from its own context helper (ms_elapsed).
        // Keep the fixed 840s HTTP deadline below the configured 900s invocation.
        // A platform termination still leaves the durable job recoverable.
      }
    }
    const timeout = Math.min(WORKER_TIMEOUT_MS, remaining - SHUTDOWN_MARGIN_MS);
    if (!Number.isFinite(remaining) || !Number.isFinite(timeout) || timeout < 1000) {
      throw new Error('Insufficient worker execution time.');
    }
    let result;
    try {
      // Never accept a URL or a credential in event data, and never follow redirects
      // with the worker bearer token. Await the body before acknowledging the event.
      result = await requestWorker(`${config.origin}/internal/background-work/${event.jobId}/run`,
        config.secret, timeout, requestImpl);
    } catch (_) {
      // Do not attach the original error, response body, request headers or URL:
      // those can contain runtime credentials or user data from an upstream.
      throw new Error('Worker invocation could not be confirmed; retry is required.');
    }
    if (!result || !['done', 'busy', 'retry', 'failed'].includes(result.status)) {
      throw new Error('Worker returned an invalid completion status; retry is required.');
    }
    if (result.status === 'busy' || result.status === 'retry') {
      throw new Error('Worker execution is unfinished; retry is required.');
    }
    // A terminal failed job is retained in MySQL for administrator recovery.
    // Repeated platform invocations must not repeat an ambiguous WeChat send.
    return { jobId: event.jobId, status: result.status };
  };
}

exports.main_handler = createHandler();
exports.createHandler = createHandler;
exports.runtimeConfig = runtimeConfig;
exports.requestWorker = requestWorker;
