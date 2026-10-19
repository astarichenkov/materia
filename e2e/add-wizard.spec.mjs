import { test, expect } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = process.env.LP_E2E_FIXTURE || path.resolve(here, '..', 'target', 'e2e-test.mp4');
const realUpload = process.env.LP_E2E_UPLOAD === '1';

async function openUrlPanel(page) {
  await page.getByText('Вставить ссылку', { exact: true }).click();
  await expect(page.locator('[data-source-panel="url"]')).toBeVisible();
}

test('add wizard opens with three sources and no Support entry points', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  const response = await page.goto('/add');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Добавить материал', level: 1 })).toBeVisible();

  const radios = page.locator('input[name="importSource"]');
  await expect(radios).toHaveCount(3);
  await expect(page.locator('[data-source-panel="file"]')).toBeVisible();
  await expect(page.locator('[data-source-panel="url"]')).toBeHidden();
  await expect(page.locator('[data-source-panel="discovery"]')).toBeHidden();

  await page.locator('.source-switch').getByText('Найти видео', { exact: true }).click();
  await expect(page.locator('[data-source-panel="discovery"]')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Перейти к поиску видео' })).toBeVisible();

  await expect(page.locator('a[href^="/support"]')).toHaveCount(0);
  await expect(page.locator('.sidebar a[href="/support"]')).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('scenario picker is rendered before processing options', async ({ page }) => {
  await page.goto('/add');
  await expect(page.getByText('Что сделать с материалом?').first()).toBeVisible();
  await expect(page.locator('[data-source-form="file"] input[name="scenario"]')).toHaveCount(13);
  await expect(page.locator('[data-source-panel="file"] .scenario-card')
    .filter({ hasText: 'Рецепт' })).toBeVisible();
  await expect(page.locator('[data-source-panel="file"] .scenario-card')
    .filter({ hasText: 'Без изменений' })).toBeVisible();
  await expect(page.locator('[data-source-panel="file"] input[name="scenario"][value="recipe"]'))
    .toBeVisible();
  await page.locator('[data-source-panel="file"] .scenario-card')
    .filter({ hasText: 'Рецепт' }).click();
  await expect(page.locator('[data-source-panel="file"] input[name="scenario"][value="recipe"]'))
    .toBeChecked();
});

test('source switch and keyboard navigation work', async ({ page }) => {
  await page.goto('/add');
  const urlRadio = page.locator('input[name="importSource"][value="url"]');
  await urlRadio.focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('input[name="importSource"][value="discovery"]')).toBeChecked();
  await expect(page.locator('[data-source-panel="discovery"]')).toBeVisible();

  await page.keyboard.press('ArrowLeft');
  await expect(urlRadio).toBeChecked();
  await expect(page.getByLabel('Ссылка на видео')).toBeVisible();
  // Field is reachable with Tab from the source switch.
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Ссылка на видео')).toBeFocused();
});

test('unsupported local file is rejected before sending', async ({ page }) => {
  await page.goto('/add');
  const input = page.locator('input[type=file][name=file]');
  await input.setInputFiles({
    name: 'lecture.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('not a video')
  });
  await expect(page.locator('[data-file-error]')).toContainText('Формат не поддерживается');
  await expect(page.locator('[data-file-selected]')).toContainText('lecture.txt');

  await page.getByRole('button', { name: 'Добавить и начать обработку' }).first().click();
  await expect(page).toHaveURL(/\/add$/);
  await expect(page.getByRole('heading', { name: 'Добавить материал', level: 1 })).toBeVisible();
});

test('selected file and options are summarised before submit', async ({ page }) => {
  test.skip(!realUpload, 'set LP_E2E_UPLOAD=1 to use the real video fixture');
  await page.goto('/add');
  await page.locator('input[type=file][name=file]').setInputFiles(fixture);
  const localForm = page.locator('form[action="/lectures"]');
  await localForm.locator('select[name=translationMode]').selectOption('ORIGINAL_ONLY');
  const summary = localForm.locator('[data-import-summary]');
  await expect(summary).toBeVisible();
  await expect(summary).toContainText('файл с компьютера');
  await expect(summary).toContainText('только оригинал');
  await expect(summary).toContainText('Сценарий');
  await expect(summary).toContainText('Обработка');
  await expect(summary).toContainText('Результат');
});

test('local upload creates one lecture and it appears in the library', async ({ page }) => {
  test.skip(!realUpload, 'set LP_E2E_UPLOAD=1 to run a real upload');
  await page.goto('/add');
  await page.locator('input[type=file][name=file]').setInputFiles(fixture);
  const localForm = page.locator('form[action="/lectures"]');
  await localForm.locator('select[name=languageMode]').selectOption('RUSSIAN');
  await localForm.locator('select[name=translationMode]').selectOption('ORIGINAL_ONLY');
  await localForm.locator('details.advanced summary').click();
  await localForm.locator('input[name=autoTranslate]').uncheck();

  await page.getByRole('button', { name: 'Добавить и начать обработку' }).first().click();
  await expect(page).toHaveURL(/\/lectures\/[0-9a-f-]{36}/);
  await expect(page.locator('body')).toContainText('Статус:');
  await expect(page.locator('[data-status]').first()).toHaveAttribute('data-status',
    /UPLOADED|DOWNLOADING|VALIDATING|EXTRACTING_AUDIO|TRANSCRIBING|TRANSLATING|PROCESSING_TEXT|EXTRACTING_SLIDES|EXPORTING|COMPLETED|PARTIAL|FAILED|CANCELLED/);

  const lectureId = new URL(page.url()).pathname.match(/\/lectures\/([0-9a-f-]{36})/)[1];
  await page.goto('/library');
  await expect(page.getByRole('heading', { name: 'Библиотека', level: 1 })).toBeVisible();
  await expect(page.locator('a[href="/lectures/' + lectureId + '"]').first()).toBeVisible();
});

test('double click does not send the upload twice', async ({ page }) => {
  test.skip(!realUpload, 'set LP_E2E_UPLOAD=1 to run a real upload');
  let posts = 0;
  await page.route('**/lectures', async (route) => {
    if (route.request().method() === 'POST') {
      posts += 1;
      await new Promise((resolve) => setTimeout(resolve, 800));
    }
    await route.continue();
  });
  await page.goto('/add');
  await page.locator('input[type=file][name=file]').setInputFiles(fixture);
  const button = page.getByRole('button', { name: 'Добавить и начать обработку' }).first();
  await button.click({ noWaitAfter: true });
  await page.waitForTimeout(150);
  // A second click while the first request is in flight must be inert.
  const submitButton = page.locator('form[action="/lectures"] [data-submit-button]');
  if (await submitButton.count()) {
    await submitButton.click({ force: true, noWaitAfter: true }).catch(() => {});
  }
  await page.waitForURL(/\/lectures\//);
  expect(posts).toBe(1);
});

test('url preview explains an unsupported platform and offers fallbacks', async ({ page }) => {
  await page.goto('/add');
  await openUrlPanel(page);
  await page.getByLabel('Ссылка на видео').fill('https://example.com/lecture');
  await page.getByRole('button', { name: 'Проверить ссылку' }).click();

  const preview = page.locator('#url-preview');
  await expect(preview).toContainText('Не удалось получить информацию о видео');
  await expect(preview).toContainText('Неподдерживаемая платформа');
  await expect(preview.getByRole('button', { name: 'Загрузить файл' })).toBeVisible();
  await expect(preview.getByRole('link', { name: 'Открыть видео на платформе' })).toBeVisible();
  await expect(preview.getByRole('link', { name: 'Диагностика' })).toBeVisible();
});

test('url form error keeps the entered link and creates nothing', async ({ page }) => {
  await page.goto('/add');
  await openUrlPanel(page);
  const link = 'https://example.com/example-lecture';
  await page.getByLabel('Ссылка на видео').fill(link);
  await page.getByRole('button', { name: 'Добавить и начать обработку' }).first().click();

  await expect(page).toHaveURL(/\/add.*[?&]url=/);
  await expect(page.getByRole('alert').first()).toContainText('Материал не добавлен');
  await expect(page.getByLabel('Ссылка на видео')).toHaveValue(link);
  await expect(page.locator('[data-source-panel="url"]')).toBeVisible();
});

test('local audio is supported and slides stay unavailable for it', async ({ page }) => {
  await page.goto('/add');
  const accept = await page.locator('input[type=file][name=file]').getAttribute('accept');
  expect(accept).toMatch(/mp3/);
  expect(accept).toMatch(/m4a/);
  expect(accept).toMatch(/wav/);
  await expect(page.locator('[data-source-panel="file"]')).toContainText('MP3, M4A, WAV, FLAC');

  // Selecting an audio file disables the slides option (no video frames exist).
  await page.locator('input[type=file][name=file]').setInputFiles({
    name: 'lecture.mp3', mimeType: 'audio/mpeg', buffer: Buffer.from('ID3')
  });
  const slides = page.locator('form[action="/lectures"] input[name=slides]');
  await expect(slides).toBeDisabled();
  await expect(slides).not.toBeChecked();
  await expect(page.locator('[data-audio-note]')).toBeVisible();
});

test('wizard is usable at 390 px and 1440 px without page overflow', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  for (const width of [390, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const response = await page.goto('/add');
    expect(response.status()).toBe(200);
    await expect(page.getByRole('heading', { name: 'Добавить материал', level: 1 })).toBeVisible();
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `add overflows at ${width}`).toBeLessThanOrEqual(1);
    await openUrlPanel(page);
    const overflowUrl = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflowUrl, `url panel overflows at ${width}`).toBeLessThanOrEqual(1);
  }
  expect(errors).toEqual([]);
});

test('discovery result links back to the wizard when a search runs', async ({ page }) => {
  const response = await page.goto('/discover?query=machine+learning');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Smart Video Discovery', level: 1 })).toBeVisible();
  const handoff = page.getByRole('link', { name: 'Открыть в мастере' }).first();
  if (await handoff.count()) {
    await handoff.click();
    await expect(page).toHaveURL(/\/add.*[?&]url=/);
    await expect(page.locator('[data-source-panel="url"]')).toBeVisible();
    await expect(page.getByLabel('Ссылка на видео')).not.toHaveValue('');
  } else {
    test.info().annotations.push({
      type: 'blocked',
      description: 'No discovery results (provider keys or network unavailable)'
    });
  }
});
