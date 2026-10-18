import { test, expect } from '@playwright/test';
import path from 'node:path';

test('library renders and navigation is not broken', async ({ page }) => {
  const response = await page.goto('/');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Lecture Processor' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Загрузить и обработать' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Проверить' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Скачать и обработать' })).toBeVisible();

  await page.getByRole('link', { name: /Smart Video Discovery/ }).click();
  await expect(page).toHaveURL(/\/discover/);
  await expect(page.getByRole('heading', { name: 'Smart Video Discovery' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Найти' })).toBeVisible();

  await page.getByRole('link', { name: /← К лекциям/ }).click();
  await expect(page).toHaveURL(/\/$/);
});

test('diagnostics page opens and reports checks', async ({ page }) => {
  const response = await page.goto('/diagnostics');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Диагностика установки' })).toBeVisible();
  await expect(page.locator('body')).toContainText('Java runtime');
  await expect(page.locator('body')).toContainText('SQLite');
});

test('settings, assistant, support, file agent, code review and benchmarks open', async ({ page }) => {
  for (const path of ['/settings', '/assistant', '/support', '/file-agent', '/code-review', '/benchmarks']) {
    const response = await page.goto(path);
    expect(response.status(), `${path} status`).toBe(200);
    await expect(page.locator('body')).not.toContainText('Whitelabel Error Page');
    await expect(page.locator('body')).not.toContainText('Whitelabel');
  }
});

test('unknown page shows the friendly error page, not Whitelabel', async ({ page }) => {
  const response = await page.goto('/this-page-does-not-exist');
  expect(response.status()).toBe(404);
  await expect(page.locator('body')).toContainText('404');
  await expect(page.locator('body')).toContainText('Correlation ID');
  await expect(page.locator('body')).not.toContainText('Whitelabel');
});

test('local upload opens a lecture card', async ({ page }) => {
  test.skip(process.env.LP_E2E_UPLOAD !== '1', 'set LP_E2E_UPLOAD=1 to run a real upload');
  await page.goto('/');
  const fixture = process.env.LP_E2E_FIXTURE || path.resolve('target/e2e-test.mp4');
  await page.locator('input[type=file][name=file]').setInputFiles(fixture);
  // Keep the smoke test local: no cloud translation is needed for this clip.
  const localForm = page.locator('form[action="/lectures"]');
  await localForm.locator('select[name=languageMode]').selectOption('RUSSIAN');
  await localForm.locator('select[name=translationMode]').selectOption('ORIGINAL_ONLY');
  await localForm.locator('input[name=autoTranslate]').uncheck();
  await page.getByRole('button', { name: 'Загрузить и обработать' }).click();
  await expect(page).toHaveURL(/\/lectures\//);
  await expect(page.locator('body')).toContainText('Статус:');
  await expect(page.locator('body')).toContainText(/UPLOADED|DOWNLOADING|VALIDATING|EXTRACTING_AUDIO|TRANSCRIBING|TRANSLATING|PROCESSING_TEXT|EXTRACTING_SLIDES|EXPORTING|COMPLETED|PARTIAL|FAILED|CANCELLED/);
});
