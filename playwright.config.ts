import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:5173', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [
    { name: 'tablet', use: { browserName: 'chromium', viewport: { width: 1280, height: 800 }, hasTouch: true } },
    { name: 'phone', use: { browserName: 'chromium', viewport: { width: 412, height: 915 }, hasTouch: true, isMobile: true } },
  ],
  webServer: { command: 'npm run dev', url: 'http://127.0.0.1:5173', reuseExistingServer: !process.env.CI, timeout: 60_000 },
});
