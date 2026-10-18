import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

export const backendUrl = process.env.E2E_API_URL || 'http://127.0.0.1:8080';
export const fixturePath = (name: string) => path.resolve('tests/fixtures/generated', name);
export const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
export const delay = (milliseconds: number) => new Promise(resolve => setTimeout(resolve, milliseconds));

// Business-flow tests explicitly dismiss the first-use guide after its server state has loaded.
export async function dismissOnboarding(page: Page) {
    await expect(page.getByTestId('onboarding-root')).toHaveAttribute('data-ready', 'true');
    if (await page.getByTestId('onboarding-dialog').isVisible()) {
        await page.getByTestId('onboarding-skip').click();
        await expect(page.getByTestId('onboarding-dialog')).not.toBeVisible();
    }
}

export class Client {
    constructor(public request: APIRequestContext, public token = '') {}

    async raw(method: string, route: string, body?: unknown, idempotencyKey?: string) {
        return this.request.fetch(`${backendUrl}/api/v1${route}`, {
            method,
            headers: {
                ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
                ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
            },
            ...(body === undefined ? {} : { data: body })
        });
    }

    async call(method: string, route: string, body?: unknown, idempotencyKey?: string): Promise<any> {
        const response = await this.raw(method, route, body, idempotencyKey);
        const text = await response.text();
        expect(response.ok(), `${method} ${route}: ${response.status()} ${text}`).toBeTruthy();
        const result = JSON.parse(text);
        expect(result.code).toBe('OK');
        return result.data;
    }

    async list(route: string): Promise<any[]> {
        const result = await this.call('GET', route);
        return result.items;
    }
}

export async function loginAdmin(request: APIRequestContext) {
    const client = new Client(request);
    const login = await client.call('POST', '/admin/auth/login', {
        username: process.env.E2E_ADMIN_USER || 'admin',
        password: process.env.E2E_ADMIN_PASSWORD || 'LocalTrain123!'
    });
    client.token = login.accessToken;
    return client;
}

export async function createLearner(admin: Client, request: APIRequestContext, approve = true) {
    const key = unique('learner');
    const person = await admin.call('POST', '/admin/people', {
        personNo: key, name: `本地测试 ${key.slice(-8)}`, unit: 'E2E 测试单位', project: '本地隔离验证'
    });
    const invitation = await admin.call('POST', `/admin/people/${person.id}/invitation`, {});
    const learner = new Client(request);
    const login = await learner.call('POST', '/auth/wechat-login', { code: `mock:${key}` });
    learner.token = login.accessToken;
    const binding = await learner.call('POST', '/me/binding-requests', { inviteCode: invitation.inviteCode });
    if (approve) {
        await admin.call('POST', `/admin/bindings/${binding.id}/decision`, { decision: 'APPROVE', reason: '本地测试人员已核对' });
    }
    return { learner, person, binding, invitation, login, key };
}

export async function uploadMaterial(admin: Client, name: string, type: 'MP4' | 'PDF') {
    const bytes = await readFile(fixturePath(name));
    const uploaded = await admin.call('POST', '/admin/materials/uploads', {
        title: unique(type === 'MP4' ? '测试视频' : '测试文档'), type, size: bytes.length, filename: name
    });
    const url = uploaded.uploadUrl.startsWith('http') ? uploaded.uploadUrl : `${backendUrl}${uploaded.uploadUrl}`;
    const transfer = await admin.request.put(url, { data: bytes, headers: { 'Content-Type': 'application/octet-stream' } });
    expect(transfer.ok(), await transfer.text()).toBeTruthy();
    const id = uploaded.materialId || uploaded.id;
    await admin.call('POST', `/admin/materials/${id}/upload-complete`, {});
    let material: any;
    await expect.poll(async () => {
        material = await admin.call('GET', `/admin/materials/${id}`);
        return material.status;
    }, { timeout: 60_000, message: 'Actual media metadata and PDF page rendering must finish' }).toBe('READY');
    await admin.call('POST', `/admin/materials/${id}/compatibility-check`, { passed: true });
    return material;
}

export async function publishTask(admin: Client, personIds: string[], materials: any[]) {
    const task = await admin.call('POST', '/admin/tasks', {
        title: unique('E2E 安全学习'), description: '仅为本地端到端测试样本',
        dueAt: new Date(Date.now() + 86400_000).toISOString(), personIds,
        materials: materials.map(material => ({
            materialId: material.id,
            rule: material.type === 'MP4' ? { playbackRate: 1 } : { minPageVisibleMs: 2000 }
        }))
    });
    const publishKey = unique('publish');
    const published = await admin.call('POST', `/admin/tasks/${task.id}/publish`, {}, publishKey);
    const assignments = await admin.list(`/admin/tasks/${task.id}/assignments`);
    return { task, published, assignments, publishKey };
}

export async function subscribe(learner: Client, result = 'accept') {
    const subscription = await learner.call('POST', '/me/subscriptions', {});
    await learner.call('POST', `/me/subscriptions/${subscription.id}/result`, { nonce: subscription.nonce, result });
    return subscription;
}

export function videoSample(seq: number, elapsedMs: number, positionMs: number, overrides = {}) {
    return {
        eventId: unique('event'), seq, type: 'VIDEO_SAMPLE', elapsedMs,
        payload: { positionMs, playing: true, playbackRate: 1, buffering: false, appVisible: true, pageVisible: true, playerVisible: true, ...overrides }
    };
}

export async function sendEvents(learner: Client, sessionId: string, events: any[]) {
    return learner.call('POST', `/learning/sessions/${sessionId}/events:batch`, {
        batchId: unique('batch'), clientVersion: 'local-e2e', events
    });
}
