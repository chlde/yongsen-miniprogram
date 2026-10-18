import { test, expect } from '@playwright/test';
import { dismissOnboarding, fixturePath, unique } from './helpers';

test('真实浏览器操作：管理员建人发任务，学员播放视频和阅读PDF，确认后导出', async ({ browser, baseURL }) => {
    test.setTimeout(240_000);
    const adminContext = await browser.newContext({ acceptDownloads: true, viewport: { width: 1440, height: 1000 } });
    const learnerContext = await browser.newContext({ viewport: { width: 430, height: 932 } });
    const admin = await adminContext.newPage();
    const learner = await learnerContext.newPage();
    const identity = unique('browser');
    const personName = `界面测试 ${identity.slice(-6)}`;
    const videoTitle = unique('UI 视频');
    const pdfTitle = unique('UI 文档');
    const taskTitle = unique('UI 完整学习任务');
    try {
        await admin.goto(baseURL!);
        await admin.getByTestId('admin-password').fill(process.env.E2E_ADMIN_PASSWORD || 'LocalTrain123!');
        await admin.getByTestId('login-submit').click();
        await dismissOnboarding(admin);
        await admin.getByTestId('nav-people').click();
        await admin.getByTestId('person-number').fill(identity);
        await admin.getByTestId('person-name').fill(personName);
        await admin.getByTestId('person-unit').fill('本地 UI 验收');
        await admin.getByTestId('person-save').click();
        const personRow = admin.getByTestId(`person-row-${identity}`);
        await expect(personRow).toBeVisible();
        await personRow.getByTestId('person-invite').click();
        const inviteCode = await admin.getByTestId('invite-code').innerText();

        await learner.goto(`${baseURL}/#learner`);
        await learner.getByTestId('role-learner').click();
        await learner.getByTestId('learner-identity').fill(identity);
        await learner.getByTestId('login-submit').click();
        await learner.getByTestId('learner-invite').fill(inviteCode);
        await learner.getByTestId('learner-bind').click();
        await expect(learner.getByTestId('binding-pending')).toBeVisible();
        await admin.getByTestId('people-refresh').click();
        const bindingRow = admin.getByTestId(`binding-${personName}`);
        await bindingRow.getByTestId('binding-approve').click();
        await learner.getByTestId('learner-refresh-binding').click();
        await expect(learner.getByTestId('learner-subscribe')).toBeVisible();
        await learner.getByTestId('learner-subscribe').click();
        await expect(learner.getByTestId('subscription-id')).toBeVisible();

        await admin.getByTestId('nav-materials').click();
        for (const [title, filename] of [[videoTitle, 'training.mp4'], [pdfTitle, 'training.pdf']]) {
            await admin.getByTestId('material-title').fill(title);
            await admin.getByTestId('material-file').setInputFiles(fixturePath(filename));
            await admin.getByTestId('material-upload').click();
            const row = admin.getByTestId(`material-row-${title}`);
            await expect(row.getByTestId('material-preview-button')).toBeEnabled({ timeout: 60_000 });
            await row.getByTestId('material-preview-button').click();
            if (filename.endsWith('.mp4')) {
                await expect.poll(() => admin.getByTestId('material-preview-video').evaluate((node: HTMLVideoElement) => node.readyState)).toBeGreaterThanOrEqual(1);
            } else {
                await expect.poll(() => admin.getByTestId('material-preview-page').evaluate((node: HTMLImageElement) => node.complete && node.naturalHeight > 0)).toBe(true);
            }
            await admin.getByTestId('material-compatibility-checkbox').check();
            await admin.getByTestId('material-approve').click();
            await expect(row).toContainText('已确认');
        }

        await admin.getByTestId('nav-tasks').click();
        await admin.getByTestId('task-create').click();
        await admin.getByTestId('task-title').fill(taskTitle);
        // The configured notification template includes a deadline field.
        const deadline = new Date(Date.now() + 86400_000);
        const localDeadline = new Date(deadline.getTime() - deadline.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
        await admin.getByTestId('task-due-at').fill(localDeadline);
        await admin.getByTestId(`select-material-${videoTitle}`).check();
        await admin.getByTestId(`select-material-${pdfTitle}`).check();
        await admin.getByTestId(`select-person-${identity}`).check();
        await admin.getByTestId('task-page-seconds').fill('2');
        await admin.getByTestId('task-publish').click();
        await expect(admin.getByTestId('task-tab-notifications')).toBeVisible();
        await admin.getByTestId('nav-outbox').click();
        const card = admin.getByTestId('outbox-card').filter({ hasText: taskTitle });
        await expect(async () => {
            await admin.getByTestId('outbox-refresh').click();
            await expect(card).toBeVisible();
        }).toPass({ timeout: 15_000 });
        await card.getByTestId('outbox-deliver').click();
        const deepLink = await card.getByTestId('outbox-entry').inputValue();
        await learner.goto(deepLink);
        await learner.bringToFront();
        await expect(learner.getByRole('heading', { name: taskTitle, exact: true })).toBeVisible();
        await expect(learner.getByTestId('learner-confirm')).toBeDisabled();

        await learner.getByTestId(`learner-material-${videoTitle}`).getByTestId('learner-start-material').click();
        await expect(learner.getByTestId('learning-start')).toBeEnabled();
        await learner.getByTestId('learning-start').click();
        await expect.poll(() => learner.getByTestId('learning-video').evaluate((node: HTMLVideoElement) => node.currentTime), { timeout: 15_000 }).toBeGreaterThan(2);
        await expect(learner.getByTestId('learning-progress')).toHaveText('已达到本材料学习要求', { timeout: 40_000 });
        await learner.getByTestId('learning-finish').click();

        await learner.getByTestId(`learner-material-${pdfTitle}`).getByTestId('learner-start-material').click();
        await expect(learner.getByTestId('learning-start')).toBeEnabled();
        await learner.getByTestId('learning-start').click();
        for (let page = 1; page <= 2; page += 1) {
            await expect(learner.getByTestId('pdf-page-number')).toContainText(`第 ${page} / 2 页`);
            await expect.poll(() => learner.getByTestId('learning-pdf-page').evaluate((node: HTMLImageElement) => node.complete && node.naturalHeight > 0)).toBe(true);
            // Real waiting and scrolling: no clock jumps, fake progress or backend completion shortcuts.
            await learner.waitForTimeout(6100);
            await learner.getByTestId('pdf-reader').evaluate(node => { node.scrollTop = node.scrollHeight; });
            await learner.waitForTimeout(6100);
            if (page === 1) {
                await learner.getByTestId('pdf-next').click();
            }
        }
        await expect(learner.getByTestId('learning-progress')).toHaveText('已达到本材料学习要求', { timeout: 15_000 });
        await learner.getByTestId('learning-finish').click();
        await expect(learner.getByTestId('learner-confirm-checkbox')).toBeEnabled();
        await learner.getByTestId('learner-confirm-checkbox').check();
        await learner.getByTestId('learner-confirm').click();
        await expect(learner.getByRole('heading', { name: '已完成本任务学习要求', exact: true })).toBeVisible();
        await learner.screenshot({ path: test.info().outputPath('learner-completed.png'), fullPage: true });

        await admin.getByTestId('nav-tasks').click();
        await admin.getByTestId(`task-row-${taskTitle}`).getByTestId('task-detail').click();
        await admin.getByTestId('task-refresh').click();
        const assignment = admin.getByTestId(`assignment-row-${personName}`);
        await expect(assignment).toContainText('已完成');
        await assignment.getByTestId('assignment-progress-detail').click();
        await expect(admin.getByTestId('assignment-detail')).toContainText(videoTitle);
        const downloadPromise = admin.waitForEvent('download');
        await admin.getByTestId('task-export-csv').click();
        const download = await downloadPromise;
        await download.saveAs(test.info().outputPath('training-records.csv'));
        expect(download.suggestedFilename()).toMatch(/\.csv$/);
        const popupPromise = admin.waitForEvent('popup');
        await assignment.getByTestId('assignment-print').click();
        const popup = await popupPromise;
        await expect(popup.locator('body')).toContainText(personName);
        await admin.screenshot({ path: test.info().outputPath('admin-progress.png'), fullPage: true });
        await popup.close();
    } finally {
        await adminContext.close();
        await learnerContext.close();
    }
});
