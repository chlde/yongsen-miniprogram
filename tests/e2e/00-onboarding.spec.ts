import { test, expect, type Page } from '@playwright/test';
import { Client, loginAdmin, unique } from './helpers';

test.use({ video: 'off' });

async function signIn(page: Page) {
    await page.goto('/');
    await page.getByTestId('admin-password').fill(process.env.E2E_ADMIN_PASSWORD || 'LocalTrain123!');
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('onboarding-root')).toHaveAttribute('data-ready', 'true');
}

async function finishGuide(page: Page) {
    for (let step = 0; step < 5; step++) {
        await expect(page.getByTestId('onboarding-dialog')).toHaveAttribute('data-step', String(step));
        await page.getByTestId('onboarding-next').click();
    }
    await page.getByTestId('onboarding-finish').click();
    await expect(page.getByTestId('onboarding-dialog')).not.toBeVisible();
}

test('首次教程自动出现，跳过和完成在服务端持久化；全程不创建业务数据', async ({ page, request, browser, baseURL }) => {
    // This test deliberately runs first against the fresh database created by npm run verify.
    const admin = await loginAdmin(request);
    expect(await admin.call('GET', '/admin/onboarding')).toEqual({
        tutorialId: 'admin-basics-v1', status: 'NOT_STARTED', updatedAt: null
    });
    const routes = ['/admin/people', '/admin/materials', '/admin/tasks', '/admin/notification-config'];
    const before = await Promise.all(routes.map(route => admin.call('GET', route)));
    await page.setViewportSize({ width: 1440, height: 1000 });
    await signIn(page);
    const dialog = page.getByTestId('onboarding-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('data-step-key', 'welcome');
    await page.getByTestId('onboarding-skip').click();
    await expect(dialog).not.toBeVisible();
    const dismissed = await admin.call('GET', '/admin/onboarding');
    expect(dismissed.status).toBe('DISMISSED');
    expect(dismissed.updatedAt).toBeTruthy();
    await page.reload();
    await expect(page.getByTestId('onboarding-root')).toHaveAttribute('data-ready', 'true');
    await expect(dialog).not.toBeVisible();

    const businessWrites: string[] = [];
    page.on('request', req => {
        if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method()) && !req.url().endsWith('/admin/onboarding')) {
            businessWrites.push(`${req.method()} ${req.url()}`);
        }
    });
    await page.getByTestId('onboarding-replay').click();
    await page.getByTestId('onboarding-next').click();
    await expect(dialog).toHaveAttribute('data-step-key', 'people');
    await expect(page.getByTestId('onboarding-highlight')).toHaveAttribute('data-target', 'people');
    await page.screenshot({ path: test.info().outputPath('admin-onboarding-desktop.png') });
    await page.getByTestId('onboarding-back').click();
    const steps = [
        ['welcome', 'overview'], ['people', 'people'], ['materials', 'materials'],
        ['notifications', 'settings'], ['publish', 'tasks'], ['progress', 'tasks']
    ];
    for (const [index, [key, target]] of steps.entries()) {
        await expect(dialog).toHaveAttribute('data-step', String(index));
        await expect(dialog).toHaveAttribute('data-step-key', key);
        await expect(page.getByTestId('onboarding-highlight')).toHaveAttribute('data-target', target);
        await expect(page.getByTestId(`nav-${target}`)).toBeVisible();
        if (index < steps.length - 1) await page.getByTestId('onboarding-next').click();
    }
    await page.getByTestId('onboarding-finish').click();
    await expect(dialog).not.toBeVisible();
    const completed = await admin.call('GET', '/admin/onboarding');
    expect(completed.status).toBe('COMPLETED');
    expect(await admin.call('PUT', '/admin/onboarding', { tutorialId: 'admin-basics-v1', status: 'DISMISSED' })).toEqual(completed);
    expect(businessWrites).toEqual([]);
    expect(await Promise.all(routes.map(route => admin.call('GET', route)))).toEqual(before);

    const cleanContext = await browser.newContext({ baseURL });
    try {
        const freshPage = await cleanContext.newPage();
        await signIn(freshPage);
        await expect(freshPage.getByTestId('onboarding-dialog')).not.toBeVisible();
        await expect(freshPage.getByTestId('onboarding-replay')).toBeVisible();
    } finally { await cleanContext.close(); }
});

test('手机重播不丢失未提交表单，键盘焦点留在教程内并可退出', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page);
    await page.getByTestId('nav-people').click();
    await page.getByTestId('person-number').fill('UNSAVED-ONBOARDING');
    await page.getByTestId('person-name').fill('尚未提交');
    await page.getByTestId('onboarding-replay').click();
    const dialog = page.getByTestId('onboarding-dialog');
    await expect(dialog).toBeVisible();
    for (let key = 0; key < 10; key++) {
        await page.keyboard.press('Tab');
        expect(await dialog.evaluate(node => node.contains(document.activeElement))).toBe(true);
    }
    await page.getByTestId('onboarding-next').click();
    for (let step = 1; step < 6; step++) {
        await expect(dialog).toHaveAttribute('data-step', String(step));
        const bounds = await dialog.boundingBox();
        expect(bounds).not.toBeNull();
        expect(bounds!.x).toBeGreaterThanOrEqual(0);
        expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(391);
        expect(bounds!.y).toBeGreaterThanOrEqual(0);
        expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(845);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        if (step === 1) await page.screenshot({ path: test.info().outputPath('admin-onboarding-mobile.png') });
        if (step < 5) await page.getByTestId('onboarding-next').click();
    }
    await page.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await expect(page.getByTestId('person-number')).toHaveValue('UNSAVED-ONBOARDING');
    await expect(page.getByTestId('person-name')).toHaveValue('尚未提交');
    await expect(page.getByTestId('onboarding-replay')).toBeFocused();
});

test('教程读取或保存失败时说明未保存，可继续业务并重试', async ({ page, request }) => {
    const admin = await loginAdmin(request);
    const initial = await admin.call('GET', '/admin/onboarding');
    const fail = (route: import('@playwright/test').Route) => route.fulfill({ status: 503,
        contentType: 'application/json', body: JSON.stringify({ code: 'UNAVAILABLE', message: '测试：教程服务暂不可用' }) });
    await page.route('**/api/v1/admin/onboarding', fail);
    await signIn(page);
    await expect(page.getByTestId('onboarding-dialog')).not.toBeVisible();
    await page.getByTestId('nav-people').click();
    await expect(page.getByTestId('person-save')).toBeVisible();
    await page.getByTestId('onboarding-replay').click();
    await page.getByTestId('onboarding-skip').click();
    await expect(page.getByTestId('onboarding-dialog')).toBeVisible();
    await expect(page.getByTestId('onboarding-dialog')).toContainText('未保存');
    await page.getByTestId('onboarding-close-unsaved').click();
    await expect(page.getByTestId('onboarding-dialog')).not.toBeVisible();
    await expect(page.getByTestId('person-save')).toBeVisible();
    expect(await admin.call('GET', '/admin/onboarding')).toEqual(initial);
    await page.getByTestId('onboarding-replay').click();
    await page.getByTestId('onboarding-skip').click();
    await expect(page.getByTestId('onboarding-save-error')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('onboarding-dialog')).not.toBeVisible();
    await expect(page.getByTestId('onboarding-notice')).toContainText('尚未保存');
    await page.getByTestId('onboarding-replay').click();
    await page.getByTestId('onboarding-skip').click();
    await expect(page.getByTestId('onboarding-save-error')).toBeVisible();
    await page.unroute('**/api/v1/admin/onboarding', fail);
    await page.getByTestId('onboarding-retry').click();
    await expect(page.getByTestId('onboarding-dialog')).not.toBeVisible();
    await page.getByTestId('onboarding-replay').click();
    await finishGuide(page);
    expect((await admin.call('GET', '/admin/onboarding')).status).toBe('COMPLETED');
});

test('教程接口限制当前管理员，拒绝未登录、学员及非法版本和状态', async ({ request }) => {
    const guest = new Client(request);
    expect((await guest.raw('GET', '/admin/onboarding')).status()).toBe(401);
    const identity = await guest.call('POST', '/auth/wechat-login', { code: `mock:${unique('onboarding-role')}` });
    const learner = new Client(request, identity.accessToken);
    expect((await learner.raw('GET', '/admin/onboarding')).status()).toBe(403);
    expect((await learner.raw('PUT', '/admin/onboarding', { tutorialId: 'admin-basics-v1', status: 'COMPLETED' })).status()).toBe(403);
    const admin = await loginAdmin(request);
    for (const body of [
        { tutorialId: 'unknown', status: 'COMPLETED' },
        { tutorialId: 'admin-basics-v1', status: 'NOT_STARTED' },
        { tutorialId: 'admin-basics-v1' }
    ]) expect((await admin.raw('PUT', '/admin/onboarding', body)).status()).toBe(400);
});
