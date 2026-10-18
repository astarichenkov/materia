import { test, expect } from '@playwright/test';

const lectureId = process.env.LP_E2E_LECTURE;

// These scenarios need a real processed lecture (thumbnail + transcript) in an
// isolated test instance. They never mutate the lecture: the format change is
// only previewed, not submitted.
test.describe('nightly roadmap: covers and selective re-run', () => {
  test.skip(!lectureId, 'set LP_E2E_LECTURE=<lecture-id> to run against a processed lecture');

  test('library shows a real cover and keeps the card/list toggle', async ({ page }) => {
    await page.goto('/library?view=grid');
    const list = page.locator('.library-list--grid');
    await expect(list).toBeVisible();
    const cover = list.locator(`img[src*="/api/lectures/${lectureId}/thumbnail"]`).first();
    await expect(cover).toBeVisible();
    // Natural width proves the endpoint returned a decodable image, not a 404.
    await expect.poll(async () => cover.evaluate((img) => img.naturalWidth))
      .toBeGreaterThan(0);

    await page.getByRole('link', { name: 'Список' }).click();
    await expect(page).toHaveURL(/view=list/);
    await expect(page.locator('.library-list--list')).toBeVisible();

    await page.getByRole('link', { name: 'Карточки' }).click();
    await expect(page.locator('.library-list--grid')).toBeVisible();
  });

  test('lecture card shows the cover and the profile form', async ({ page }) => {
    await page.goto(`/lectures/${lectureId}`);
    const cover = page.locator('.lecture-hero__thumb img');
    await expect(cover).toBeVisible();
    await expect.poll(async () => cover.evaluate((img) => img.naturalWidth))
      .toBeGreaterThan(0);
    await expect(page.getByRole('heading', { name: 'Результат и шаблон' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Пересоздать результат' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'История результатов' })).toBeVisible();
  });

  test('changing the format previews a plan without re-running Whisper', async ({ page }) => {
    await page.goto(`/lectures/${lectureId}`);
    const plan = page.locator('#rerun-plan');
    await expect(plan).toContainText('Транскрипция уже готова');
    await expect(plan).toContainText('Whisper не запускается');

    await page.locator('#profile-format').selectOption('BRIEF_NOTES');
    await expect(plan).toContainText('notes-brief-v1');
    await expect(plan).toContainText('Изменился формат');
    // The preview must not navigate away or start a job.
    await expect(page).toHaveURL(new RegExp(`/lectures/${lectureId}$`));
    await expect(page.getByRole('button', { name: 'Пересоздать результат' })).toBeVisible();
  });

  test('mobile lecture card and library do not overflow', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    for (const route of ['/library', `/lectures/${lectureId}`]) {
      await page.goto(route);
      const overflow = await page.evaluate(() =>
        document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${route} overflows on mobile`).toBeLessThanOrEqual(1);
    }
  });
});
