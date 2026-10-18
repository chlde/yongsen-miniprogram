import { test, expect } from '@playwright/test';
import { loginAdmin, createLearner, uploadMaterial, publishTask, sendEvents, videoSample, unique, delay } from './helpers';

test('管理员不能冒充学员绑定、记录打开或完成；撤销绑定立即阻止继续学习', async ({ request }) => {
    const admin = await loginAdmin(request);
    const { learner, person, binding } = await createLearner(admin, request);
    const material = await uploadMaterial(admin, 'training.mp4', 'MP4');
    const { assignments } = await publishTask(admin, [person.id], [material]);
    const assignmentId = assignments[0].id;
    const adminInvite = await admin.call('POST', `/admin/people/${person.id}/invitation`, {});
    for (const [route, body] of [
        ['/me/binding-requests', { inviteCode: adminInvite.inviteCode }],
        [`/me/assignments/${assignmentId}/entered`, {}],
        [`/me/assignments/${assignmentId}/confirm`, { accepted: true, confirmationTextVersion: 'mvp-1', progressDigest: '' }]
    ] as const) {
        const response = await admin.raw('POST', route, body, unique('guard'));
        expect(response.ok(), route).toBe(false);
    }
    const detail = await learner.call('GET', `/me/assignments/${assignmentId}`);
    const session = await learner.call('POST', '/learning/sessions', { assignmentId, taskMaterialId: detail.materials[0].taskMaterialId });
    await sendEvents(learner, session.sessionId, [videoSample(1, 0, 0)]);
    await admin.call('POST', `/admin/bindings/${binding.id}/revoke`, { reason: '本地撤销绑定验收' });
    const revoked = await learner.raw('POST', `/learning/sessions/${session.sessionId}/events:batch`, {
        batchId: unique('revoked'), events: [videoSample(2, 1000, 1000)]
    });
    expect(revoked.ok()).toBe(false);
    expect((await learner.raw('GET', `/me/assignments/${assignmentId}`)).ok()).toBe(false);
    const persisted = await admin.call('GET', `/admin/assignments/${assignmentId}`);
    expect(persisted.materials[0].progress.effectiveMs).toBe(0);
    expect(persisted.status).not.toBe('COMPLETED');
});

test('缺号补传保持顺序和幂等；超过会话租期后不接纳离线进度', async ({ request }) => {
    const admin = await loginAdmin(request);
    const { learner, person } = await createLearner(admin, request);
    const material = await uploadMaterial(admin, 'training.mp4', 'MP4');
    const { assignments } = await publishTask(admin, [person.id], [material]);
    const assignmentId = assignments[0].id;
    const detail = await learner.call('GET', `/me/assignments/${assignmentId}`);
    const session = await learner.call('POST', '/learning/sessions', { assignmentId, taskMaterialId: detail.materials[0].taskMaterialId });
    await sendEvents(learner, session.sessionId, [videoSample(1, 0, 0)]);
    await delay(1010);
    const second = videoSample(2, 1010, 1010);
    await delay(1010);
    const third = videoSample(3, 2020, 2020);
    const outOfOrder = await sendEvents(learner, session.sessionId, [third]);
    expect(outOfOrder.verifiedSeq).toBe(1);
    expect(outOfOrder.missingSeqRanges).toContainEqual([2, 2]);
    const repaired = await sendEvents(learner, session.sessionId, [second]);
    expect(repaired.verifiedSeq).toBe(3);
    expect(repaired.missingSeqRanges).toHaveLength(0);
    expect(repaired.confirmedProgress.effectiveMs).toBeGreaterThanOrEqual(1800);
    const repeated = await sendEvents(learner, session.sessionId, [second, third]);
    expect(repeated.confirmedProgress.effectiveMs).toBe(repaired.confirmedProgress.effectiveMs);
    await delay(30_200);
    const late = await sendEvents(learner, session.sessionId, [videoSample(4, 32_220, 12_000)]);
    expect(late.sessionValid).toBe(false);
    expect(late.confirmedProgress.effectiveMs).toBe(repaired.confirmedProgress.effectiveMs);
    expect(late.confirmedProgress.requirementsMet).toBe(false);
});

test('500人任务完整分页且重复发布不重复分配，不能超过MVP上限', async ({ request }) => {
    const admin = await loginAdmin(request);
    const people = [];
    const prefix = unique('capacity');
    for (let index = 0; index < 500; index++) {
        people.push(await admin.call('POST', '/admin/people', {
            personNo: `${prefix}-${index}`, name: `分页验收${index}`, unit: '本地数据', project: prefix
        }));
    }
    const material = await uploadMaterial(admin, 'training.pdf', 'PDF');
    // 满足已配置通知模板的时间字段契约，保持本用例聚焦容量和发布幂等。
    const dueAt = new Date(Date.now() + 86400_000).toISOString();
    const task = await admin.call('POST', '/admin/tasks', { title: prefix, dueAt, personIds: people.map(row => row.id),
        materials: [{ materialId: material.id, rule: { minPageVisibleMs: 30000 } }] });
    const key = unique('publish500');
    const published = await admin.call('POST', `/admin/tasks/${task.id}/publish`, {}, key);
    expect(published.assignmentCount).toBe(500);
    const seen = new Set<string>();
    let cursor: string | null = null;
    let pages = 0;
    do {
        const result = await admin.call('GET', `/admin/tasks/${task.id}/assignments${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`);
        expect(result.items.length).toBeLessThanOrEqual(100);
        result.items.forEach((row: any) => seen.add(row.personId));
        cursor = result.nextCursor;
        pages++;
        expect(pages).toBeLessThanOrEqual(5);
    } while (cursor);
    expect(seen.size).toBe(500);
    expect((await admin.call('POST', `/admin/tasks/${task.id}/publish`, {}, key)).assignmentCount).toBe(500);
    const tooMany = await admin.raw('POST', '/admin/tasks', { title: prefix, dueAt,
        personIds: [...people.map(row => row.id), 'extra-person'], materials: [{ materialId: material.id }] });
    expect(tooMany.ok()).toBe(false);
    await admin.call('POST', `/admin/tasks/${task.id}/cancel`, { reason: '容量边界验收结束，停止待发送通知' });
});

test('材料超过100条时可以通过游标继续查看', async ({ request }) => {
    const admin = await loginAdmin(request);
    const prefix = unique('material-pages');
    for (let index = 0; index < 101; index++) {
        await admin.call('POST', '/admin/materials/uploads', {
            title: `${prefix}-${index}`, type: 'PDF', size: 10, filename: 'pending.pdf'
        });
    }
    const first = await admin.call('GET', `/admin/materials?q=${prefix}`);
    expect(first.items).toHaveLength(100);
    expect(first.nextCursor).toBeTruthy();
    const second = await admin.call('GET', `/admin/materials?q=${prefix}&cursor=${encodeURIComponent(first.nextCursor)}`);
    expect(second.items).toHaveLength(1);
    expect(second.nextCursor).toBeNull();
    expect(first.items.some((item: any) => item.id === second.items[0].id)).toBe(false);
});
