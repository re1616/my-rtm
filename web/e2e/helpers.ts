import type { Page } from '@playwright/test';

/** A fresh browser starts with the introduction (WEB-F11); scenarios that are not about it skip it. */
export async function skipOnboarding(page: Page): Promise<void> {
  const skip = page.getByRole('button', { name: 'Пропустить' });
  try {
    await skip.waitFor({ state: 'visible', timeout: 3_000 });
  } catch {
    return; // already done in this browser profile
  }
  await skip.click();
  await page.getByRole('heading', { name: 'Входящие' }).waitFor();
}
