import { readFile, mkdir, writeFile, stat } from 'node:fs/promises';
import path from 'node:path';

// Credentials and returned tokens never appear in URLs, process arguments, logs or reports.
const secretFile = path.resolve('.local/secrets/wechat.json');
const reportDir = path.resolve('.local/wechat-readiness');
const report = { checkedAt: new Date().toISOString(), endpoint: 'https://api.weixin.qq.com/cgi-bin/stable_token',
    scope: 'Credential validity only; not user login, cloud deployment or notification delivery.', status: 'FAILED' };
try {
    const permissions = await stat(secretFile);
    if (permissions.mode & 0o077) throw new Error('PRIVATE_FILE_PERMISSIONS');
    const credentials = JSON.parse(await readFile(secretFile, 'utf8'));
    if (!/^wx[a-zA-Z0-9]{16}$/.test(credentials.appId) || !/^[a-f0-9]{32}$/.test(credentials.appSecret)) {
        throw new Error('INVALID_PRIVATE_CONFIG');
    }
    report.appId = credentials.appId;
    const response = await fetch(report.endpoint, { method: 'POST', redirect: 'error',
        headers: { 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(15000),
        body: JSON.stringify({ grant_type: 'client_credential', appid: credentials.appId,
            secret: credentials.appSecret, force_refresh: false }) });
    report.httpStatus = response.status;
    const data = await response.json();
    if (!response.ok || data.errcode || typeof data.access_token !== 'string' || !data.access_token
        || !Number.isFinite(data.expires_in) || data.expires_in <= 0) {
        report.wechatErrorCode = Number.isInteger(data.errcode) ? data.errcode : null;
        throw new Error('WECHAT_REJECTED');
    }
    report.status = 'PASSED';
    report.expiresInSeconds = data.expires_in;
} catch (error) {
    const safeCodes = new Set(['PRIVATE_FILE_PERMISSIONS', 'INVALID_PRIVATE_CONFIG', 'WECHAT_REJECTED']);
    report.failure = safeCodes.has(error.message) ? error.message : 'NETWORK_OR_CONFIG_ERROR';
    process.exitCode = 1;
}
await mkdir(reportDir, { recursive: true, mode: 0o700 });
await writeFile(path.join(reportDir, 'credential-check.json'), `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
console.log(JSON.stringify(report, null, 2));
