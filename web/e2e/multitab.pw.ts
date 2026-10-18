// WEB-S5 in a real browser: one sync leader per browser, handover when it closes, and server
// changes pushed over the WebSocket reach the other tabs.
import { test, expect, type Page } from '@playwright/test';

const API = process.env.TASKLY_API ?? 'http://localhost:8080';

type SyncDebug = { __tasklySync?: { isLeader(): boolean } };
const isLeader = (p: Page) => p.evaluate(() => (window as unknown as SyncDebug).__tasklySync?.isLeader() ?? null);

async function open(page: Page) {
  await page.goto('/');
  await page.waitForFunction(() => (window as unknown as SyncDebug).__tasklySync !== undefined);
}

test('exactly one tab leads, and the next one takes over within 2 s', async ({ context }) => {
  const a = await context.newPage();
  await open(a);
  await a.waitForFunction(() => (window as unknown as SyncDebug).__tasklySync!.isLeader() === true);

  const b = await context.newPage();
  await open(b);
  await b.waitForTimeout(500);
  expect(await isLeader(a)).toBe(true);
  expect(await isLeader(b)).toBe(false);

  const closedAt = Date.now();
  await a.close();
  await b.waitForFunction(() => (window as unknown as SyncDebug).__tasklySync!.isLeader() === true, null, { timeout: 2_000 });
  expect(Date.now() - closedAt).toBeLessThan(2_000);
});

test('a change made elsewhere reaches a follower tab via the leader', async ({ context, request }) => {
  const email = `pw-${Date.now()}-${Math.random().toString(16).slice(2)}@taskly.test`;
  const password = 'Playwright-1';

  const leader = await context.newPage();
  await open(leader);
  await leader.getByPlaceholder('email').fill(email);
  await leader.getByPlaceholder('пароль').fill(password);
  await leader.getByRole('button', { name: 'Регистрация' }).click();
  await expect(leader.getByText(email)).toBeVisible();

  const follower = await context.newPage();
  await open(follower);
  expect(await isLeader(follower)).toBe(false);
  await expect(follower.getByText(email)).toBeVisible(); // same account in every tab

  // Another device (here: the API directly) adds a task
  const login = await request.post(`${API}/v1/auth/login`, { data: { email, password } });
  expect(login.ok()).toBe(true);
  const { accessToken } = await login.json();
  const name = `Из другого устройства ${Date.now()}`;
  const created = await request.post(`${API}/v1/tasks`, {
    data: { name },
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  expect(created.status()).toBe(201);

  // Server → WebSocket → leader pulls → BroadcastChannel → follower reloads "Входящие"
  await expect(follower.getByText(name)).toBeVisible({ timeout: 20_000 });
  await expect(leader.getByText(name)).toBeVisible();
});
