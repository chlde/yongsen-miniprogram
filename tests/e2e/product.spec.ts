import { test, expect } from '@playwright/test';
import { dismissOnboarding } from './helpers';

// These layout checks retain screenshots and traces; they do not require video encoding.
test.use({ video: 'off' });

async function signIn(page: import('@playwright/test').Page) {
    await page.getByTestId('admin-password').fill(process.env.E2E_ADMIN_PASSWORD || 'LocalTrain123!');
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('training-overview')).toBeVisible();
    await dismissOnboarding(page);
}

test('企业培训主题入口只预填任务，不自动选择人员、材料或发布；桌面与手机布局可用', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '登录企业培训平台' })).toBeVisible();
    await expect(page.locator('body')).not.toContainText('留痕');
    await expect(page.locator('body')).not.toContainText('本人确认');
    await page.screenshot({ path: test.info().outputPath('enterprise-login.png'), fullPage: true });
    await signIn(page);
    await expect(page.getByTestId('training-theme-safety')).toBeVisible();
    await expect(page.locator('.overview-stat .stat-value strong').first()).not.toHaveText('—');
    await expect(page.getByTestId('training-overview')).not.toContainText('本人确认');
    await page.screenshot({ path: test.info().outputPath('enterprise-overview.png'), fullPage: true });
    const writes: string[] = [];
    page.on('request', request => {
        if (request.method() === 'POST' && request.url().includes('/admin/tasks')) writes.push(request.url());
    });
    for (const [theme, title] of [['safety', '安全生产专题培训'], ['skills', '岗位技能专题培训'], ['growth', '员工能力提升培训']]) {
        await page.getByTestId(`training-theme-${theme}`).click();
        await expect(page.getByTestId('task-title')).toHaveValue(title);
        await expect(page.getByTestId('task-description')).not.toHaveValue('');
        await expect(page.locator('input[type="checkbox"]:checked')).toHaveCount(0);
        await expect(page.getByText('已预填培训名称与目标。', { exact: false })).toBeVisible();
        await page.getByTestId('nav-overview').click();
    }
    expect(writes).toEqual([]);
    const recent = page.locator('[data-testid^="overview-task-"]');
    if (await recent.count()) {
        await recent.first().click();
        await expect(page.getByTestId('task-tab-notifications')).toBeVisible();
        await page.getByTestId('nav-overview').click();
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByTestId('training-theme-growth')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: test.info().outputPath('enterprise-overview-mobile.png'), fullPage: true });
    await page.getByTestId('logout').click();
    await expect(page.getByTestId('login-submit')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: test.info().outputPath('enterprise-login-mobile.png'), fullPage: true });
});

test('工作台数据获取失败时不伪造为零，恢复请求后显示真实数据', async ({ page }) => {
    await page.route('**/api/v1/admin/tasks?*', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'UNAVAILABLE', message: '测试：培训数据暂不可用' }) }));
    await page.goto('/');
    await signIn(page);
    await expect(page.getByText('测试：培训数据暂不可用')).toBeVisible();
    const metrics = page.locator('.overview-stat .stat-value strong');
    await expect(metrics).toHaveText(['—', '—', '—', '—']);
    await page.unroute('**/api/v1/admin/tasks?*');
    await page.getByRole('button', { name: '刷新工作台' }).click();
    await expect(metrics.first()).not.toHaveText('—');
});

test('处理状态支持翻页查找旧任务，恢复失败会保留记录并允许重试', async ({ page }) => {
    const recoveryId = 'b'.repeat(64);
    let recovered = false;
    let retryAttempts = 0;
    const offsets: number[] = [];
    await page.route('**/api/v1/admin/background-work?*', route => {
        const offset = Number(new URL(route.request().url()).searchParams.get('offset') || 0);
        offsets.push(offset);
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
            code: 'OK', data: { hasMore: offset === 0, items: [{
                id: offset === 0 ? 'a'.repeat(64) : recoveryId,
                kind: offset === 0 ? 'NOTIFICATIONS' : 'MEDIA',
                status: offset === 0 ? 'RUNNING' : recovered ? 'DISPATCHED' : 'FAILED',
                attempts: 2, leaseUntilMs: Date.now() + 60_000,
                errorCode: offset === 0 ? '' : 'CLOUD_STORAGE_UNAVAILABLE'
            }] }
        }) });
    });
    await page.route(`**/api/v1/admin/background-work/${recoveryId}/retry`, route => {
        retryAttempts++;
        recovered = retryAttempts > 1;
        return route.fulfill({ status: recovered ? 200 : 503, contentType: 'application/json',
            body: JSON.stringify(recovered ? { code: 'OK', data: { queued: true } }
                : { code: 'UNAVAILABLE', message: '处理服务暂不可用，请稍后重试' }) });
    });
    await page.goto('/');
    await signIn(page);
    await page.getByTestId('nav-settings').click();
    const panel = page.getByTestId('background-work-panel');
    await expect(panel.getByText('正在处理', { exact: true })).toBeVisible();
    await expect(panel.getByRole('button', { name: '重新处理' })).toHaveCount(0);
    await panel.getByRole('button', { name: '下一页' }).click();
    await expect(panel.getByText('第 2 页')).toBeVisible();
    await expect(panel.getByText('教材处理', { exact: true })).toBeVisible();
    await panel.getByRole('button', { name: '重新处理' }).click();
    await expect(panel.getByText('处理服务暂不可用，请稍后重试')).toBeVisible();
    await expect(panel.getByText('处理失败', { exact: true })).toBeVisible();
    await panel.getByRole('button', { name: '重新处理' }).click();
    await expect(panel.getByText('已排队', { exact: true })).toBeVisible();
    await panel.getByRole('button', { name: '刷新处理状态' }).click();
    await expect(panel.getByText('第 1 页')).toBeVisible();
    expect(offsets).toEqual([0, 100, 100, 0]);
});
