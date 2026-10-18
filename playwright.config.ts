import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    timeout: 180_000,
    expect: { timeout: 15_000 },
    fullyParallel: false,
    workers: 1,
    retries: 0,
    outputDir: process.env.E2E_RESULTS_DIR || 'test-results',
    reporter: [['list'], ['html', { open: 'never', outputFolder: process.env.E2E_HTML_REPORT || 'playwright-report' }],
        ['json', { outputFile: `${process.env.E2E_RESULTS_DIR || 'test-results'}/e2e-results.json` }]],
    use: {
        baseURL: process.env.E2E_WEB_URL || 'http://127.0.0.1:5173',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        video: 'retain-on-failure'
    },
    projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }]
});
