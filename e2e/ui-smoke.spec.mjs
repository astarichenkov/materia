import { test, expect } from '@playwright/test';
import path from 'node:path';

const pages = ['/', '/library', '/add', '/jobs', '/discover', '/diagnostics', '/settings',
  '/assistant', '/support', '/file-agent', '/code-review', '/benchmarks'];

test('home renders and navigation is not broken', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  const response = await page.goto('/');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Lecture Processor', level: 1 })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Добавить материал' }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'Найти видео' }).first()).toBeVisible();
  await expect(page.getByLabel('Ссылка на видео — откроем форму с уже заполненным адресом')).toBeVisible();

  await page.goto('/add');
  await expect(page.getByRole('heading', { name: 'Добавить материал', level: 1 })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Добавить и начать обработку' }).first()).toBeVisible();
  // The URL controls live in the second wizard panel; switching is required.
  await expect(page.getByRole('button', { name: 'Проверить ссылку' })).toBeHidden();
  await page.getByText('Вставить ссылку', { exact: true }).click();
  await expect(page.getByRole('button', { name: 'Проверить ссылку' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Добавить и начать обработку' }).first()).toBeVisible();

  await page.locator('.sidebar').getByRole('link', { name: 'Поиск видео' }).click();
  await expect(page).toHaveURL(/\/discover/);
  await expect(page.getByRole('heading', { name: 'Smart Video Discovery', level: 1 })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Найти' })).toBeVisible();

  await page.getByRole('link', { name: /Lecture Processor/ }).first().click();
  await expect(page).toHaveURL(/\/$/);
  expect(errors).toEqual([]);
});

test('desktop navigation has approved groups and no Support', async ({ page }) => {
  await page.goto('/');
  const sidebar = page.locator('.sidebar');
  for (const name of ['Обзор', 'Библиотека', 'Добавить материал', 'Задачи обработки', 'Поиск видео']) {
    await expect(sidebar.getByRole('link', { name, exact: true })).toBeVisible();
  }
  await expect(sidebar.locator('summary', { hasText: 'Инструменты разработчика' })).toBeVisible();
  await expect(sidebar.locator('summary', { hasText: 'Система' })).toBeVisible();
  await expect(sidebar.locator('a[href="/support"]')).toHaveCount(0);
  await expect(page.locator('a[href^="/support"]')).toHaveCount(0);
  await expect(sidebar.locator('a[href="/mcp"]')).toHaveCount(0);

  await sidebar.locator('summary', { hasText: 'Инструменты разработчика' }).click();
  await expect(sidebar.getByRole('link', { name: 'Developer Assistant' })).toBeVisible();
  await expect(sidebar.getByRole('link', { name: 'Code Review' })).toBeVisible();
  await expect(sidebar.getByRole('link', { name: 'File Agent' })).toBeVisible();
});

test('mobile drawer navigates and does not overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  const toggle = page.getByRole('button', { name: 'Открыть меню' });
  await expect(toggle).toBeVisible();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#app-sidebar')).toHaveClass(/is-open/);
  await page.locator('.sidebar').getByRole('link', { name: 'Библиотека', exact: true }).click();
  await expect(page).toHaveURL(/\/library/);
  await expect(page.getByRole('heading', { name: 'Библиотека', level: 1 })).toBeVisible();
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
});

test('all pages open without runtime errors and no horizontal page scroll', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  for (const width of [1440, 1024, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    for (const route of ['/', '/library', '/discover', '/settings', '/diagnostics', '/code-review']) {
      const response = await page.goto(route);
      expect(response.status(), `${route} at ${width}`).toBe(200);
      await expect(page.locator('body')).not.toContainText('Whitelabel');
      const overflow = await page.evaluate(() =>
        document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${route} overflows at ${width}`).toBeLessThanOrEqual(1);
    }
  }
  expect(errors).toEqual([]);
});

test('all existing and new workspace pages render', async ({ page }) => {
  for (const route of pages) {
    const response = await page.goto(route);
    expect(response.status(), `${route} status`).toBe(200);
    await expect(page.locator('body')).not.toContainText('Whitelabel Error Page');
  }
});

test('support stays reachable by direct URL only', async ({ page }) => {
  const response = await page.goto('/support');
  expect(response.status()).toBe(200);
  await expect(page.locator('body')).toContainText('Поддержка');
  await expect(page.locator('.sidebar')).toHaveCount(0);
});

test('htmx is served locally and works offline', async ({ page }) => {
  await page.route('https://unpkg.com/**', (route) => route.abort());
  await page.goto('/');
  const scripts = await page.locator('script[src]').evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('src')));
  expect(scripts.some((src) => src && src.includes('unpkg.com'))).toBe(false);
  expect(await page.evaluate(() => typeof window.htmx)).toBe('object');
});

test('settings forms keep their CSRF hidden fields', async ({ page }) => {
  const response = await page.goto('/settings');
  expect(response.status()).toBe(200);
  const csrf = page.locator('form input[name="_csrf"]');
  expect(await csrf.count()).toBeGreaterThan(0);
  const first = await csrf.first().inputValue();
  expect(first.length).toBeGreaterThan(10);
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
  await page.goto('/add');
  const fixture = process.env.LP_E2E_FIXTURE || path.resolve('..', 'target', 'e2e-test.mp4');
  await page.locator('input[type=file][name=file]').setInputFiles(fixture);
  const localForm = page.locator('form[action="/lectures"]');
  await localForm.locator('select[name=languageMode]').selectOption('RUSSIAN');
  await localForm.locator('select[name=translationMode]').selectOption('ORIGINAL_ONLY');
  await localForm.locator('details.advanced summary').click();
  await localForm.locator('input[name=autoTranslate]').uncheck();
  await page.getByRole('button', { name: 'Добавить и начать обработку' }).first().click();
  await expect(page).toHaveURL(/\/lectures\//);
  await expect(page.locator('body')).toContainText('Статус:');
  await expect(page.locator('[data-status]').first()).toHaveAttribute('data-status',
    /UPLOADED|DOWNLOADING|VALIDATING|EXTRACTING_AUDIO|TRANSCRIBING|TRANSLATING|PROCESSING_TEXT|EXTRACTING_SLIDES|EXPORTING|COMPLETED|PARTIAL|FAILED|CANCELLED/);
});

test('lecture card and editor render without Support links', async ({ page }) => {
  const id = process.env.LP_E2E_LECTURE;
  test.skip(!id, 'set LP_E2E_LECTURE=<lecture-id> to run against a filled library');
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  for (const route of [`/lectures/${id}`, `/lectures/${id}/editor`]) {
    const response = await page.goto(route);
    expect(response.status(), route).toBe(200);
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await expect(page.locator('a[href^="/support"]')).toHaveCount(0);
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `${route} overflow`).toBeLessThanOrEqual(1);
  }
  expect(errors).toEqual([]);
});

test('dashboard quick URL carries the link to the add form', async ({ page }) => {
  await page.goto('/');
  const url = 'https://example.com/lecture';
  await page.getByLabel('Ссылка на видео — откроем форму с уже заполненным адресом').fill(url);
  await page.getByRole('button', { name: 'Продолжить' }).click();
  await expect(page).toHaveURL(/\/add\?url=/);
  await expect(page.getByLabel('Ссылка на видео')).toHaveValue(url);
});

test('library filters and pagination keep their query contract', async ({ page }) => {
  await page.goto('/library');
  await expect(page.getByRole('heading', { name: 'Библиотека', level: 1 })).toBeVisible();
  await page.getByLabel('Поиск по названию или теме').fill('zzz-no-such-lecture');
  await page.getByLabel('Статус').selectOption('COMPLETED');
  await page.getByRole('button', { name: 'Применить' }).click();
  await expect(page).toHaveURL(/q=zzz-no-such-lecture/);
  await expect(page).toHaveURL(/status=COMPLETED/);
  await expect(page.locator('.empty-state').first()).toBeVisible();
  await expect(page.locator('.empty-state').first()).toContainText(
    /Библиотека пока пуста|По выбранным условиям ничего нет/);
  await page.getByRole('link', { name: 'Сбросить фильтры' }).first().click();
  await expect(page).toHaveURL(/\/library$/);
});
