import { defineConfig } from '@playwright/test';

const baseURL = process.env.LP_BASE_URL || 'http://127.0.0.1:18090';

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.mjs',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL,
    headless: true,
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
  },
});
