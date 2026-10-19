// QA-5: a task created offline on Android appears in the web app within 2 s.
// The Android side is `CrossClientE2ETest` (Robolectric, the production sync engine); both run
// at the same time against the same server and exchange the account and the push time through
// files in QA5_DIR. Run by CI as a separate step (see ci.yml); ignored otherwise.
import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { skipOnboarding } from './helpers';

test('QA-5: a task created offline on Android is visible in the web app within 2 s', async ({ page }) => {
  test.setTimeout(300_000);
  const dir = process.env.QA5_DIR;
  expect(dir, 'QA5_DIR must be set').toBeTruthy();
  const email = `qa5-${Date.now()}-${Math.random().toString(16).slice(2)}@taskly.test`;
  const password = 'CrossClient-1';

  await page.goto('/');
  await skipOnboarding(page);
  await page.getByPlaceholder('email').fill(email);
  await page.getByPlaceholder('пароль').fill(password);
  await page.getByRole('button', { name: 'Регистрация' }).click();
  await expect(page.getByText(email)).toBeVisible();
  // Signed in and connected: the WebSocket is open before Android pushes
  await page.waitForTimeout(1_000);

  fs.writeFileSync(path.join(dir!, 'creds.json'), JSON.stringify({ email, password }));

  const marker = path.join(dir!, 'pushed.json');
  await expect.poll(() => fs.existsSync(marker), { timeout: 240_000, intervals: [50] }).toBe(true);
  const { name, pushedAt } = JSON.parse(fs.readFileSync(marker, 'utf8')) as { name: string; pushedAt: number };

  await expect(page.getByText(name)).toBeVisible({ timeout: 10_000 });
  const latency = Date.now() - pushedAt;
  // Printed by the CI step, so GitHub shows it as an annotation
  console.log(`::notice title=QA-5 Android → web latency::${latency} ms (limit 2000 ms)`);
  expect(latency).toBeLessThanOrEqual(2_000);
});
