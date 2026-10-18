// End-to-end AC-7 against a real Taskly server (runs in CI after `./gradlew :server:run`).
// Skipped unless TASKLY_E2E_URL is set, e.g. TASKLY_E2E_URL=http://localhost:8080.
import { describe, it, expect, beforeEach } from 'vitest';
import { getDb, initializeDb } from '../db/database';
import { createTask, completeTask, getPendingSyncOps, getTasksByList } from '../entities/taskRepository';
import { pushPendingOps, syncNow } from '../features/sync/SyncService';
import { apiRequest, register } from '../api/client';

const baseUrl = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env
  .TASKLY_E2E_URL;

let online = true;
const flakyFetch: typeof fetch = (input, init) => {
  if (!online) return Promise.reject(new TypeError('Failed to fetch'));
  return globalThis.fetch(input, init);
};
const deps = { fetchImpl: flakyFetch, baseUrl };

describe.skipIf(!baseUrl)('offline sync against the real server', () => {
  let token = '';

  beforeEach(async () => {
    await initializeDb();
    const db = getDb();
    await Promise.all([db.tasks.clear(), db.syncQueue.clear(), db.settings.clear()]);
    online = true;
    const session = await register(deps, `e2e-${crypto.randomUUID()}@taskly.test`, 'E2e-password-1');
    token = session.accessToken;
  });

  it('task created offline reaches the API after the connection is back', async () => {
    online = false;
    const task = await createTask({ name: 'Офлайн задача', priority: 2, dueDate: '2026-12-01' });
    expect((await pushPendingOps(deps)).state).toBe('offline');
    expect(await getPendingSyncOps()).toHaveLength(1);

    online = true;
    expect(await syncNow(deps)).toBe('idle');
    expect(await getPendingSyncOps()).toHaveLength(0);

    const res = await apiRequest<{ data: Array<{ id: string; name: string; priority: number; dueAt: string }> }>(
      deps, 'GET', `/v1/tasks?q=${encodeURIComponent('priority:2')}`, undefined, token,
    );
    const found = res.data.find((t) => t.id === task.id);
    expect(found).toMatchObject({ name: 'Офлайн задача', priority: 2 });
    expect(found?.dueAt).toBeTruthy();
  });

  it('completing a repeating task offline: the server creates the next instance and it is pulled back', async () => {
    const task = await createTask({
      name: 'Еженедельно',
      dueDate: '2026-10-19T10:00:00+03:00',
      repeatRule: 'FREQ=WEEKLY',
      repeatMode: 'every',
    });
    expect(await syncNow(deps)).toBe('idle');

    online = false;
    await completeTask(task.id);
    online = true;
    expect(await syncNow(deps)).toBe('idle');

    const open = (await getTasksByList('sys-all')).filter((t) => t.name === 'Еженедельно');
    expect(open).toHaveLength(1);
    expect(open[0].id).not.toBe(task.id);
    expect(new Date(open[0].dueDate!).toISOString()).toBe(new Date('2026-10-26T10:00:00+03:00').toISOString());
  });
});
