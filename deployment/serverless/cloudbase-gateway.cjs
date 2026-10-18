const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');
const { Transform } = require('node:stream');

const MAX_BODY = 19 * 1024 * 1024;
const REQUEST_HEADERS = new Set(['authorization', 'content-type', 'content-length',
    'accept', 'range', 'if-none-match', 'idempotency-key']);
const RESPONSE_HEADERS = new Set(['content-type', 'content-length', 'content-disposition',
    'content-range', 'accept-ranges', 'cache-control', 'etag', 'last-modified', 'location']);
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
    '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.webp': 'image/webp' };

function productionUpstream(value) {
    const url = new URL(value);
    if (url.protocol !== 'https:' || !url.hostname.endsWith('.tencentscf.com')
        || url.port || url.username || url.password || url.search || url.hash
        || url.pathname !== '/') throw new Error('Expected an HTTPS SCF origin');
    return url;
}

function requestPath(raw) {
    if (!raw || !raw.startsWith('/') || raw.startsWith('//') || /[\x00-\x20\x7f\\]/.test(raw)) return null;
    const pathname = raw.split('?')[0];
    let decoded;
    try { decoded = decodeURIComponent(pathname); } catch { return null; }
    if (/%(?:2f|5c|25)/i.test(pathname) || /[\x00-\x20\x7f\\]/.test(decoded)
        || decoded.split('/').some(part => part === '.' || part === '..')) return null;
    return decoded;
}

function reply(res, status, code) {
    if (res.headersSent) { res.destroy(); return; }
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ code, ...(code === 'SERVER_BUSY'
        ? { message: '服务正在处理其他请求，请稍后重试。' } : {}) }));
}

function createGateway({ upstream, staticRoot, maxBody = MAX_BODY, timeoutMs = 55000,
    maxConnections = 3, maxPendingRequests = 32 }) {
    const root = fs.realpathSync(staticRoot);
    const transport = upstream.protocol === 'https:' ? https : http;
    // SCF permits four simultaneous invocations. Leave one for the event worker;
    // the agent queues bursts without replaying writes or sharing response data.
    const agent = new transport.Agent({ keepAlive: true, maxSockets: maxConnections,
        maxFreeSockets: maxConnections });
    let pendingRequests = 0;
    const server = http.createServer({ maxHeaderSize: 16384 }, (req, res) => {
        const pathname = requestPath(req.url);
        if (!pathname) { reply(res, 400, 'INVALID_PATH'); return; }
        const api = pathname.startsWith('/api/v1/') && !/^\/api\/v1\/local(?:\/|$)/.test(pathname);
        const callback = pathname === '/integrations/wechat/yongsen/callback';
        if (api || callback || pathname === '/health') {
            if (!['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'OPTIONS'].includes(req.method)) {
                reply(res, 405, 'METHOD_NOT_ALLOWED'); return;
            }
            if (Number(req.headers['content-length'] || 0) > maxBody) {
                reply(res, 413, 'REQUEST_TOO_LARGE'); req.resume(); return;
            }
            if (pendingRequests >= maxPendingRequests) {
                reply(res, 503, 'SERVER_BUSY'); req.resume(); return;
            }
            pendingRequests++;
            res.once('close', () => { pendingRequests--; });
            const headers = Object.fromEntries(Object.entries(req.headers).filter(([key]) => REQUEST_HEADERS.has(key)));
            const target = transport.request({ protocol: upstream.protocol, hostname: upstream.hostname,
                port: upstream.port, path: req.url, method: req.method, headers, agent }, incoming => {
                const responseHeaders = Object.fromEntries(Object.entries(incoming.headers)
                    .filter(([key]) => RESPONSE_HEADERS.has(key)));
                res.writeHead(incoming.statusCode, responseHeaders);
                incoming.on('error', () => res.destroy());
                incoming.pipe(res);
            });
            let bytes = 0;
            const limit = new Transform({ transform(chunk, encoding, done) {
                bytes += chunk.length;
                if (bytes > maxBody) {
                    reply(res, 413, 'REQUEST_TOO_LARGE');
                    done(new Error('Body limit exceeded'));
                } else done(null, chunk);
            } });
            const timer = setTimeout(() => { reply(res, 504, 'UPSTREAM_TIMEOUT'); target.destroy(); }, timeoutMs);
            timer.unref();
            res.on('close', () => { clearTimeout(timer); target.destroy(); });
            req.on('aborted', () => target.destroy());
            req.on('error', () => target.destroy());
            target.on('error', () => {
                clearTimeout(timer);
                if (!res.writableEnded) reply(res, 502, 'UPSTREAM_UNAVAILABLE');
            });
            limit.on('error', () => { req.unpipe(limit); req.resume(); target.destroy(); });
            req.pipe(limit).pipe(target);
            return;
        }
        if (!['GET', 'HEAD'].includes(req.method)) { reply(res, 405, 'METHOD_NOT_ALLOWED'); return; }
        if (pathname !== '/' && !pathname.startsWith('/assets/') && !/^\/[\w.-]+\.(?:ico|svg|png|webp)$/.test(pathname)) {
            reply(res, 404, 'NOT_FOUND'); return;
        }
        const filename = path.join(root, pathname === '/' ? 'index.html' : pathname);
        fs.realpath(filename, (error, real) => {
            if (error || !real.startsWith(root + path.sep)) { reply(res, 404, 'NOT_FOUND'); return; }
            fs.stat(real, (statError, stat) => {
                if (statError || !stat.isFile()) { reply(res, 404, 'NOT_FOUND'); return; }
                res.writeHead(200, { 'Content-Type': MIME[path.extname(real)] || 'application/octet-stream',
                    'Content-Length': stat.size, 'X-Content-Type-Options': 'nosniff',
                    'Cache-Control': pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache' });
                if (req.method === 'HEAD') { res.end(); return; }
                const file = fs.createReadStream(real);
                file.on('error', () => res.destroy());
                res.on('close', () => file.destroy());
                file.pipe(res);
            });
        });
    });
    server.once('close', () => agent.destroy());
    return server;
}

if (require.main === module) {
    try {
        const upstream = productionUpstream(process.env.TRAINING_UPSTREAM_BASE);
        createGateway({ upstream, staticRoot: '/opt/yongsen/web' }).listen(9000, '0.0.0.0');
    } catch {
        console.error('Gateway startup requires a valid SCF origin and packaged web assets.');
        process.exitCode = 1;
    }
}

module.exports = { createGateway, productionUpstream };
