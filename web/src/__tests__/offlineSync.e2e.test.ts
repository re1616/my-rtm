// End-to-end AC-7 against a real Taskly server (runs in CI after `./gradlew :server:run`).
// Skipped unless TASKLY_E2E_URL is set, e.g. TASKLY_E2E_URL=http://localhost:8080.
// When TASKLY_E2E_REQUIRED=true, the test fails with an explicit message if the environment is unavailable.
import { describe, it, expect, beforeEach } from 'vitest';
import { getDb, initializeDb } from '../db/database';
import { createTask, completeTask, getPendingSyncOps, getTasksByList } from '../entities/taskRepository';
import { pushPendingOps, syncNow } from '../features/sync/SyncService';
import { apiRequest, register, setSession } from '../api/client';
import type { Session } from '../types';

const baseUrl = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env
  .TASKLY_E2E_URL;
const e2eRequired = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env
  .TASKLY_E2E_REQUIRED === 'true';

if (!baseUrl && e2eRequired) {
  throw new Error(
    'E2E tests are required (TASKLY_E2E_REQUIRED=true) but TASKLY_E2E_URL is not set. ' +
    'Cannot run offline-sync E2E tests against the real server. ' +
    'Start the server first: ./gradlew :server:run'
  );
}

let online = true;
const flakyFetch: typeof fetch = (input, init) => {
  if (!online) return new Promise((_, reject) => reject(new TypeError('Failed to fetch')));
  return globalThis.fetch(input, init);
};
const deps = { fetchImpl: flakyFetch, baseUrl };

describe.skipIf(!baseUrl)('offline sync against the real server', () => {
  let token = '';
  let session: Session | null = null;

  beforeEach(async () => {
    await initializeDb();
    const db = getDb();
    await Promise.all([db.tasks.clear(), db.syncQueue.clear(), db.settings.clear()]);
    online = true;
    session = await register(deps, `e2e-${crypto.randomUUID()}@taskly.test`, 'E2e-password-1');
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

  it('SYNC-6/7: pullChanges получает дельты списков, тегов и локаций через Taskly-Sync-Version', async () => {
    // Регистрируемся, получаем токен
    const db = getDb();

    // Создаём список и тег через API (симуляция другого устройства)
    const listId = crypto.randomUUID();
    const tagId = crypto.randomUUID();
    const locationId = crypto.randomUUID();

    // Используем sync engine для создания списка
    // (SYNC-6: клиент должен суметь применить дельты для всех сущностей scope)
    // Отправляем create операцию для списка
    await db.syncQueue.add({
      opId: crypto.randomUUID(),
      seq: Date.now(),
      scope: '',
      entity: 'list',
      entityId: listId,
      type: 'create',
      fields: { name: 'E2E Test List' },
      baseVersion: 0,
      clientTs: new Date().toISOString(),
      status: 'pending',
      retryCount: 0,
    });

    // Отправляем create операцию для тега
    await db.syncQueue.add({
      opId: crypto.randomUUID(),
      seq: Date.now() + 1,
      scope: '',
      entity: 'tag',
      entityId: tagId,
      type: 'create',
      fields: { name: 'e2e-tag' },
      baseVersion: 0,
      clientTs: new Date().toISOString(),
      status: 'pending',
      retryCount: 0,
    });

    // Отправляем create операцию для локации
    await db.syncQueue.add({
      opId: crypto.randomUUID(),
      seq: Date.now() + 2,
      scope: '',
      entity: 'location',
      entityId: locationId,
      type: 'create',
      fields: { name: 'E2E Location' },
      baseVersion: 0,
      clientTs: new Date().toISOString(),
      status: 'pending',
      retryCount: 0,
    });

    // Пушим на сервер
    const pushResult = await pushPendingOps(deps);
    // Ожидаем, что все 3 операции ушли (state === 'idle', sent может быть 0-1 из-за batch)
    // Проверяем, что сервер не отклонил операции
    expect(pushResult.state).toBe('idle');
    // Если сервер поддержал все сущности — очередь пуста
    const remaining = await getPendingSyncOps();
    expect(remaining.length).toBe(0);

    // Очищаем локальную БД, чтобы pull снова их загрузил.
    // Важно: settings содержит и сессию, и курсор — сессию восстанавливаем сразу.
    await db.lists.clear();
    await db.tags.clear();
    await db.locations.clear();
    await db.settings.clear();
    if (session) await setSession(session);

    // Pull — должен получить дельты со всеми сущностями
    const pulled = await syncNow(deps);
    expect(pulled).toBe('idle');

    // Проверяем, что список применился из дельт
    const list = await db.lists.get(listId);
    expect(list).not.toBeNull();
    expect(list!.name).toBe('E2E Test List');

    // Проверяем, что тег применился
    const tag = await db.tags.get(tagId);
    expect(tag).not.toBeNull();
    expect(tag!.name).toBe('e2e-tag');

    // Проверяем, что локация применилась
    const location = await db.locations.get(locationId);
    expect(location).not.toBeNull();
    expect(location!.name).toBe('E2E Location');
  });
});
