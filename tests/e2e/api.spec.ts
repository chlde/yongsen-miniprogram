import { test, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { Client, backendUrl, loginAdmin, createLearner, uploadMaterial, publishTask, subscribe, sendEvents, videoSample, unique, delay } from './helpers';

test('人员核对、固定任务、微信模拟渠道、真实计时学习、本人确认和导出贯通', async ({ request }) => {
    const admin = await loginAdmin(request);
    const { learner, person, binding, invitation } = await createLearner(admin, request, false);
    const pending = await learner.raw('GET', '/me/assignments');
    expect([403, 422]).toContain(pending.status());
    const replay = await learner.raw('POST', '/me/binding-requests', { inviteCode: invitation.inviteCode });
    expect(replay.ok()).toBeFalsy();
    await admin.call('POST', `/admin/bindings/${binding.id}/decision`, { decision: 'APPROVE', reason: '测试核对' });
    const video = await uploadMaterial(admin, 'training.mp4', 'MP4');
    const pdf = await uploadMaterial(admin, 'training.pdf', 'PDF');
    expect(video.durationMs).toBeGreaterThanOrEqual(11_000);
    expect(pdf.pageCount).toBe(2);

    const { task, assignments, published, publishKey } = await publishTask(admin, [person.id], [video, pdf]);
    expect(published.assignmentCount).toBe(1);
    const repeated = await admin.call('POST', `/admin/tasks/${task.id}/publish`, {}, publishKey);
    expect(repeated.assignmentCount).toBe(1);
    expect((await admin.list(`/admin/tasks/${task.id}/assignments`))).toHaveLength(1);
    const assignmentId = assignments[0].id;
    const editPublished = await admin.raw('PATCH', `/admin/tasks/${task.id}`, { title: '不允许修改历史' });
    expect(editPublished.ok()).toBeFalsy();
    const forbidden = await learner.raw('GET', '/admin/people');
    expect(forbidden.status()).toBe(403);

    let notifications = await admin.list(`/admin/tasks/${task.id}/notifications`);
    expect(notifications).toHaveLength(1);
    expect(notifications[0].deliveryStatus).not.toBe('DELIVERED');
    await subscribe(learner);
    await expect.poll(async () => {
        notifications = await admin.list(`/admin/tasks/${task.id}/notifications`);
        return notifications[0].sendStatus;
    }).toBe('ACCEPTED');
    const notice = notifications[0];
    expect(notice.deliveryStatus).toBe('UNKNOWN');
    const cards = await admin.list('/local/outbox');
    expect(cards.find(card => card.notificationId === notice.id)?.assignmentId).toBe(assignmentId);
    await admin.call('POST', `/local/notifications/${notice.id}/deliver`, { result: 'DELIVERED' });
    await admin.call('POST', `/local/notifications/${notice.id}/deliver`, { result: 'DELIVERED' });
    await learner.call('POST', `/me/assignments/${assignmentId}/entered`, { notificationId: notice.id });
    const delivered = await admin.call('GET', `/admin/notifications/${notice.id}`);
    expect(delivered.deliveryStatus).toBe('DELIVERED');
    expect(delivered.openedAt).toBeTruthy();

    let detail = await learner.call('GET', `/me/assignments/${assignmentId}`);
    const premature = await learner.raw('POST', `/me/assignments/${assignmentId}/confirm`, {
        progressDigest: detail.progressDigest, confirmationTextVersion: 'mvp-1', accepted: true
    }, unique('confirm'));
    expect(premature.status()).toBe(422);

    const videoItem = detail.materials.find((item: any) => item.type === 'MP4');
    const session = await learner.call('POST', '/learning/sessions', { assignmentId, taskMaterialId: videoItem.taskMaterialId });
    const start = Date.now();
    const baseline = videoSample(1, 0, 0);
    await sendEvents(learner, session.sessionId, [baseline]);
    let lastPosition = 0;
    let seq = 2;
    while (lastPosition < video.durationMs) {
        await delay(1010);
        lastPosition = Math.min(Date.now() - start, video.durationMs);
        await sendEvents(learner, session.sessionId, [videoSample(seq++, Date.now() - start, lastPosition)]);
    }
    const beforeReplay = await learner.call('GET', `/me/assignments/${assignmentId}/progress`);
    await sendEvents(learner, session.sessionId, [baseline]);
    const afterReplay = await learner.call('GET', `/me/assignments/${assignmentId}/progress`);
    expect(afterReplay.materials[0].progress.effectiveMs).toBe(beforeReplay.materials[0].progress.effectiveMs);
    expect(afterReplay.materials[0].progress.requirementsMet).toBe(true);
    await learner.call('POST', `/learning/sessions/${session.sessionId}/close`, { reason: '视频已学完' });

    const pdfItem = detail.materials.find((item: any) => item.type === 'PDF');
    const docSession = await learner.call('POST', '/learning/sessions', { assignmentId, taskMaterialId: pdfItem.taskMaterialId });
    const documentStart = Date.now();
    let docSeq = 1;
    for (const page of pdfItem.pages) {
        const payload = { pageNo: page.pageNo, rendered: true, visibleTopPx: 0, visibleBottomPx: page.height, appVisible: true, pageVisible: true, readerUnobscured: true };
        await sendEvents(learner, docSession.sessionId, [{ eventId: unique('doc'), seq: docSeq++, type: 'PAGE_ENTER', elapsedMs: Date.now() - documentStart, payload }]);
        for (let sample = 0; sample < 3; sample += 1) {
            await delay(1010);
            await sendEvents(learner, docSession.sessionId, [{ eventId: unique('doc'), seq: docSeq++, type: 'DOC_SAMPLE', elapsedMs: Date.now() - documentStart, payload }]);
        }
    }
    await learner.call('POST', `/learning/sessions/${docSession.sessionId}/close`, { reason: '文档已学完' });
    detail = await learner.call('GET', `/me/assignments/${assignmentId}/progress`);
    expect(detail.requirementsMet).toBe(true);
    expect(detail.status).toBe('READY_TO_CONFIRM');
    const confirmKey = unique('confirm');
    const confirmationBody = { progressDigest: detail.progressDigest, confirmationTextVersion: detail.confirmationTextVersion, accepted: true };
    const completed = await learner.call('POST', `/me/assignments/${assignmentId}/confirm`, confirmationBody, confirmKey);
    const duplicate = await learner.call('POST', `/me/assignments/${assignmentId}/confirm`, confirmationBody, confirmKey);
    expect(completed.status).toBe('COMPLETED');
    expect(duplicate.completedAt).toBe(completed.completedAt);
    const csv = await admin.raw('GET', `/admin/tasks/${task.id}/records.csv`);
    expect(csv.ok()).toBeTruthy();
    expect(await csv.text()).toContain(person.personNo);
    const print = await learner.raw('GET', `/me/assignments/${assignmentId}/print`);
    expect(print.ok()).toBeTruthy();
    expect(await print.text()).toContain(person.name);
    if (process.env.TRAINING_EVIDENCE_DIR) {
        await mkdir(process.env.TRAINING_EVIDENCE_DIR, { recursive: true });
        await writeFile(path.join(process.env.TRAINING_EVIDENCE_DIR, 'persistence-check.json'), JSON.stringify({
            assignmentId, taskId: task.id, personNo: person.personNo, completedAt: completed.completedAt,
            materials: [video, pdf].map(material => ({ id: material.id, type: material.type, sha256: material.sha256,
                pageCount: material.pageCount, pages: material.pages }))
        }, null, 2));
    }
});

test('越权、单会话、快进、后台采样、重放冲突和取消均不会制造完成', async ({ request }) => {
    const admin = await loginAdmin(request);
    const one = await createLearner(admin, request);
    const other = await createLearner(admin, request);
    const video = await uploadMaterial(admin, 'training.mp4', 'MP4');
    const { task, assignments } = await publishTask(admin, [one.person.id], [video]);
    const assignmentId = assignments[0].id;
    const forbidden = await other.learner.raw('GET', `/me/assignments/${assignmentId}`);
    expect([403, 404]).toContain(forbidden.status());
    const detail = await one.learner.call('GET', `/me/assignments/${assignmentId}`);
    const taskMaterialId = detail.materials[0].taskMaterialId;
    const session = await one.learner.call('POST', '/learning/sessions', { assignmentId, taskMaterialId });
    const baseline = videoSample(1, 0, 0);
    await sendEvents(one.learner, session.sessionId, [baseline]);
    await delay(1010);
    await sendEvents(one.learner, session.sessionId, [videoSample(2, 1010, video.durationMs)]);
    await delay(1010);
    await sendEvents(one.learner, session.sessionId, [videoSample(3, 2020, video.durationMs, { appVisible: false })]);
    const conflicting = { ...baseline, payload: { ...baseline.payload, positionMs: 5000 } };
    const conflict = await one.learner.raw('POST', `/learning/sessions/${session.sessionId}/events:batch`, { batchId: unique('batch'), events: [conflicting] });
    if (conflict.ok()) {
        const data = (await conflict.json()).data;
        expect(data.eventResults.some((result: any) => result.accepted === false)).toBe(true);
    } else {
        expect(conflict.status()).toBe(409);
    }
    const progress = await one.learner.call('GET', `/me/assignments/${assignmentId}/progress`);
    expect(progress.requirementsMet).toBe(false);
    expect(progress.materials[0].progress.coverageRatio).toBeLessThan(0.5);
    const takeover = await one.learner.call('POST', `/learning/sessions/${session.sessionId}/takeover`, { assignmentId, taskMaterialId, expectedSessionId: session.sessionId });
    expect(takeover.sessionId).not.toBe(session.sessionId);
    const old = await one.learner.raw('POST', `/learning/sessions/${session.sessionId}/events:batch`, { batchId: unique('late'), events: [videoSample(4, 3030, 1000)] });
    if (old.ok()) {
        expect((await old.json()).data.sessionValid).toBe(false);
    } else {
        expect([409, 422]).toContain(old.status());
    }
    await admin.call('POST', `/admin/tasks/${task.id}/cancel`, { reason: '本地测试取消' });
    const cancelled = await one.learner.raw('POST', '/learning/sessions', { assignmentId, taskMaterialId });
    expect(cancelled.ok()).toBeFalsy();
});

test('未知微信发送不自动重试，新授权和明确确认才允许再次提醒', async ({ request }) => {
    const admin = await loginAdmin(request);
    const { learner, person } = await createLearner(admin, request);
    const material = await uploadMaterial(admin, 'training.pdf', 'PDF');
    const { task } = await publishTask(admin, [person.id], [material]);
    const notifications = await admin.list(`/admin/tasks/${task.id}/notifications`);
    const notice = notifications[0];
    await admin.call('POST', `/local/notifications/${notice.id}/simulate`, { outcome: 'UNKNOWN' });
    await subscribe(learner);
    await expect.poll(async () => (await admin.call('GET', `/admin/notifications/${notice.id}`)).sendStatus).toBe('UNKNOWN');
    const before = await admin.call('GET', `/admin/notifications/${notice.id}`);
    await delay(3000);
    const after = await admin.call('GET', `/admin/notifications/${notice.id}`);
    expect(after.attempts.length).toBe(before.attempts.length);
    expect(after.deliveryStatus).toBe('UNKNOWN');
    const retry = await admin.raw('POST', `/admin/tasks/${task.id}/reminders`, { personIds: [person.id], reason: '不得盲目重发' }, unique('remind'));
    if (retry.ok()) {
        expect((await retry.json()).data.queuedCount).toBe(0);
    } else {
        expect([409, 422]).toContain(retry.status());
    }
    const authorization = await subscribe(learner);
    await admin.call('POST', `/admin/tasks/${task.id}/reminders`, {
        personIds: [person.id], reason: '已说明可能重复，重新授权',
        newSubscriptionId: authorization.id, confirmPossibleDuplicate: true
    }, unique('remind'));
    await expect.poll(async () => {
        const rows = await admin.list(`/admin/tasks/${task.id}/notifications`);
        return rows.filter(row => row.id !== notice.id && row.sendStatus === 'ACCEPTED').length;
    }).toBe(1);
    expect((await admin.call('GET', `/admin/notifications/${notice.id}`)).sendStatus).toBe('UNKNOWN');
});

test('文件实际格式校验、一次上传凭据和私有视频 Range 下载生效', async ({ request }) => {
    const admin = await loginAdmin(request);
    const oversized = await admin.raw('POST', '/admin/materials/uploads', {
        title: '超过大小限制', type: 'MP4', size: 501 * 1024 * 1024, filename: 'large.mp4'
    });
    expect(oversized.ok()).toBeFalsy();
    const badBytes = Buffer.from('not a pdf file');
    const upload = await admin.call('POST', '/admin/materials/uploads', {
        title: '损坏测试样本', type: 'PDF', size: badBytes.length, filename: 'broken.pdf'
    });
    const uploadUrl = upload.uploadUrl.startsWith('http') ? upload.uploadUrl : `${backendUrl}${upload.uploadUrl}`;
    expect((await request.put(uploadUrl, { data: badBytes })).ok()).toBeTruthy();
    expect((await request.put(uploadUrl, { data: badBytes })).ok()).toBeFalsy();
    await admin.call('POST', `/admin/materials/${upload.id}/upload-complete`, {});
    await expect.poll(async () => (await admin.call('GET', `/admin/materials/${upload.id}`)).status).toBe('REJECTED');
    const confirmation = await admin.raw('POST', `/admin/materials/${upload.id}/compatibility-check`, { passed: true });
    expect(confirmation.ok()).toBeFalsy();

    const material = await uploadMaterial(admin, 'training.mp4', 'MP4');
    const ticket = await admin.call('POST', `/admin/materials/${material.id}/media-ticket`, {});
    const range = await request.get(ticket.url, { headers: { Range: 'bytes=0-15' } });
    expect(range.status()).toBe(206);
    expect((await range.body()).length).toBe(16);
    expect(range.headers()['content-range']).toMatch(/^bytes 0-15\/\d+$/);
    expect(range.headers()['content-type']).toContain('video/mp4');
    const invalidRange = await request.get(ticket.url, { headers: { Range: 'bytes=999999999-' } });
    expect(invalidRange.status()).toBe(416);
    const guessed = await request.get(`${backendUrl}/api/v1/media/guessed-ticket/file`);
    expect(guessed.ok()).toBeFalsy();
});
