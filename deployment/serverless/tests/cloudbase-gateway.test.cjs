const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createGateway, productionUpstream } = require('../cloudbase-gateway.cjs');

let root, upstream, gateway, origin, received, upstreamOrigin;
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
const close = server => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
function request(urlPath, { method = 'GET', headers = {}, chunks = [], serverOrigin = origin } = {}) {
    return new Promise((resolve, reject) => {
        const req = http.request(serverOrigin + urlPath, { method, headers }, res => {
            const body = [];
            res.on('data', chunk => body.push(chunk));
            res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(body) }));
            res.on('error', reject);
        });
        req.on('error', reject);
        chunks.forEach(chunk => req.write(chunk));
        req.end();
    });
}

before(async () => {
    root = fs.mkdtempSync(path.join(os.tmpdir(), 'yongsen-gateway-'));
    fs.mkdirSync(path.join(root, 'assets'));
    fs.writeFileSync(path.join(root, 'index.html'), '<h1>永森油服</h1>');
    fs.writeFileSync(path.join(root, 'assets/app.js'), 'void 0;');
    received = [];
    upstream = http.createServer((req, res) => {
        const record = { url: req.url, headers: req.headers, method: req.method };
        received.push(record);
        if (req.url === '/api/v1/timeout') return;
        if (req.url === '/api/v1/disconnected') { req.socket.destroy(); return; }
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', () => {
            record.body = Buffer.concat(chunks).toString();
            if (req.url === '/api/v1/download') {
                res.writeHead(206, { 'Content-Type': 'application/octet-stream', 'Content-Range': 'bytes 0-2/3',
                    'Content-Disposition': 'attachment; filename="training.csv"' });
                res.end(Buffer.from([0, 128, 255]));
            } else if (req.url === '/health') {
                res.writeHead(503, { 'Content-Type': 'application/json' }); res.end('{"ready":false}');
            } else { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(record)); }
        });
    });
    const upstreamPort = await listen(upstream);
    upstreamOrigin = `http://127.0.0.1:${upstreamPort}`;
    gateway = createGateway({ upstream: new URL(upstreamOrigin), staticRoot: root, maxBody: 128, timeoutMs: 300 });
    origin = `http://127.0.0.1:${await listen(gateway)}`;
});
after(async () => { await close(gateway); await close(upstream); fs.rmSync(root, { recursive: true }); });

test('production accepts only a fixed HTTPS SCF origin', () => {
    assert.equal(productionUpstream('https://1304903095-example.ap-shanghai.tencentscf.com').protocol, 'https:');
    for (const value of ['http://127.0.0.1', 'https://evil.example', 'https://a.tencentscf.com.evil.example',
        'https://a.tencentscf.com/path', 'https://name@a.tencentscf.com', 'https://a.tencentscf.com?target=other']) {
        assert.throws(() => productionUpstream(value));
    }
});
test('serves the packaged administrator UI and assets; HEAD has no body', async () => {
    const page = await request('/'); assert.equal(page.status, 200); assert.match(page.body.toString(), /永森油服/);
    const asset = await request('/assets/app.js'); assert.match(asset.headers['cache-control'], /immutable/);
    const head = await request('/', { method: 'HEAD' }); assert.equal(head.status, 200); assert.equal(head.body.length, 0);
});
test('forwards authentication, idempotency and exact body without caller-supplied internal headers', async () => {
    const response = await request('/api/v1/admin/tasks?status=draft', { method: 'POST',
        headers: { Authorization: 'Bearer gateway-test', 'Idempotency-Key': 'task-1', 'Content-Type': 'application/json',
            'X-Worker-Secret': 'untrusted-value', 'X-WX-OPENID': 'forged', 'X-Forwarded-For': 'forged' }, chunks: ['{"title":"安全培训"}'] });
    assert.equal(response.status, 200);
    const result = JSON.parse(response.body);
    assert.equal(result.url, '/api/v1/admin/tasks?status=draft');
    assert.equal(result.headers.authorization, 'Bearer gateway-test');
    assert.equal(result.headers['idempotency-key'], 'task-1');
    assert.equal(result.body, '{"title":"安全培训"}');
    for (const key of ['x-worker-secret', 'x-wx-openid', 'x-forwarded-for']) assert.equal(result.headers[key], undefined);
});
test('internal, local-test and traversal routes never reach the backend', async () => {
    for (const route of ['/internal/background-work/1/run', '/api/v1/local/outbox', '/api/v1/local',
        '/api/v1/%2e%2e/%2e%2e/internal/run', '/api/v1/%252e%252e/internal', '/api/v1/a%2f..%2finternal', '/assets/%2e%2e/index.html']) {
        const before = received.length;
        const response = await request(route);
        assert.ok([400,404].includes(response.status), `${route}: ${response.status}`);
        assert.equal(received.length, before);
    }
});
test('rejects oversized Content-Length and chunked requests', async () => {
    assert.equal((await request('/api/v1/admin/tasks', { method:'POST',headers:{'Content-Length':'129'},chunks:['a'.repeat(129)] })).status, 413);
    assert.equal((await request('/api/v1/admin/tasks', { method:'POST',chunks:['b'.repeat(80),'c'.repeat(80)] })).status, 413);
});
test('preserves binary range responses and download metadata', async () => {
    const response = await request('/api/v1/download', { headers: { Range: 'bytes=0-2' } });
    assert.equal(response.status, 206);
    assert.deepEqual(response.body, Buffer.from([0,128,255]));
    assert.equal(response.headers['content-range'], 'bytes 0-2/3');
    assert.match(response.headers['content-disposition'], /training.csv/);
});
test('preserves callback bytes and reports actual backend health', async () => {
    const response = await request('/integrations/wechat/yongsen/callback?signature=abc', {method:'POST',chunks:['<xml>unchanged</xml>']});
    assert.equal(JSON.parse(response.body).body, '<xml>unchanged</xml>');
    assert.equal((await request('/health')).status, 503);
});
test('returns bounded errors for failed or slow upstreams', async () => {
    assert.equal((await request('/api/v1/disconnected')).status, 502);
    assert.equal((await request('/api/v1/timeout')).status, 504);
});

test('queues a dashboard burst and employee save within the SCF limit without replay or identity mixing', async () => {
    let active = 0, peak = 0;
    const seen = [];
    const limitedBackend = http.createServer((req, res) => {
        active++; peak = Math.max(peak, active);
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', () => {
            seen.push({ url: req.url, token: req.headers.authorization, body: Buffer.concat(chunks).toString() });
            setTimeout(() => { active--; res.end('{"saved":true}'); }, 30);
        });
    });
    const burstGateway = createGateway({ upstream: new URL(`http://127.0.0.1:${await listen(limitedBackend)}`),
        staticRoot: root, timeoutMs: 2000 });
    const serverOrigin = `http://127.0.0.1:${await listen(burstGateway)}`;
    try {
        const results = await Promise.all(Array.from({ length: 8 }, (_, i) => request(`/api/v1/admin/check-${i}`, {
            serverOrigin, method: i === 7 ? 'POST' : 'GET',
            headers: { Authorization: `Bearer user-${i}`, 'Content-Type': 'application/json' },
            chunks: i === 7 ? ['{"personNo":"verification"}'] : []
        })));
        assert.ok(results.every(result => result.status === 200));
        assert.equal(peak, 3);
        assert.equal(seen.length, 8);
        for (let i = 0; i < 8; i++) {
            const records = seen.filter(record => record.url === `/api/v1/admin/check-${i}`);
            assert.equal(records.length, 1);
            assert.equal(records[0].token, `Bearer user-${i}`);
        }
        assert.equal(seen.find(record => record.url.endsWith('-7')).body, '{"personNo":"verification"}');
    } finally { await close(burstGateway); await close(limitedBackend); }
});

test('bounds queued requests, keeps static pages available and releases capacity after timeouts', async () => {
    const limited = createGateway({ upstream: new URL(upstreamOrigin), staticRoot: root,
        maxConnections: 1, maxPendingRequests: 2, timeoutMs: 120 });
    const serverOrigin = `http://127.0.0.1:${await listen(limited)}`;
    try {
        const first = request('/api/v1/timeout', { serverOrigin });
        const second = request('/api/v1/timeout', { serverOrigin });
        const overflow = await request('/api/v1/admin/people', { serverOrigin, method: 'POST', chunks: ['{}'] });
        assert.equal(overflow.status, 503);
        assert.equal(JSON.parse(overflow.body).code, 'SERVER_BUSY');
        assert.equal((await request('/', { serverOrigin })).status, 200);
        assert.deepEqual((await Promise.all([first, second])).map(result => result.status), [504, 504]);
        assert.equal((await request('/api/v1/admin/people', { serverOrigin })).status, 200);
    } finally { await close(limited); }
});

test('a disconnected queued write is cancelled before it can reach the backend', async () => {
    let release, started;
    const active = new Promise(resolve => { started = resolve; });
    const seen = [];
    const backend = http.createServer((req, res) => {
        seen.push(req.url);
        if (req.url.endsWith('/hold')) { release = () => res.end('{}'); started(); }
        else { req.resume(); res.end('{}'); }
    });
    const limited = createGateway({ upstream: new URL(`http://127.0.0.1:${await listen(backend)}`),
        staticRoot: root, maxConnections: 1, timeoutMs: 2000 });
    const serverOrigin = `http://127.0.0.1:${await listen(limited)}`;
    let closed;
    const cancellationObserved = new Promise(resolve => { closed = resolve; });
    limited.on('request', (req, res) => {
        if (req.url === '/api/v1/admin/people') res.once('close', closed);
    });
    try {
        const first = request('/api/v1/hold', { serverOrigin });
        await active;
        await new Promise(resolve => {
            const queued = http.request(serverOrigin + '/api/v1/admin/people', { method: 'POST' });
            queued.on('error', () => {});
            queued.on('close', resolve);
            queued.end('{"personNo":"must-not-be-created"}', () => queued.destroy());
        });
        await cancellationObserved;
        release(); await first;
        await request('/api/v1/after', { serverOrigin });
        assert.deepEqual(seen, ['/api/v1/hold', '/api/v1/after']);
    } finally { await close(limited); await close(backend); }
});
