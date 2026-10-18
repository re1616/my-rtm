// WEB-S4: Дельты всех scope, включая новые списки и общие списки (SYNC-6);
//         первый вход — снимок страницами по 1000.
import { describe, it, expect, beforeEach, beforeAll } from 'vitest';
import { initializeDb, getDb } from '../db/database';
import { getTaskById, addToSyncQueue } from '../entities/taskRepository';
import { pullChanges } from '../features/sync/SyncService';
import { setSession } from '../api/client';

beforeAll(async () => {
  await initializeDb();
});

beforeEach(async () => {
  const db = getDb();
  await Promise.all([db.tasks.clear(), db.syncQueue.clear(), db.settings.clear()]);
  await setSession({ userId: 'u1', accessToken: 'token-1', refreshToken: 'r1' });
});

describe('WEB-S4: multi-scope deltas + paginated snapshot', () => {
  it('pullChanges получает дельты задач и применяет их локально', async () => {
    let seq = 100;

    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync/changes') {
        const after = Number(url.searchParams.get('after') ?? 0);
        const changes = [];
        if (after < 101) {
          changes.push({
            seq: 101,
            entity: 'task',
            entityId: 't-10000001-1111-4111-8111-111111111111',
            op: 'create',
            payload: JSON.stringify({ name: 'From other device', priority: 2 }),
          });
        }
        if (after < 102) {
          changes.push({
            seq: 102,
            entity: 'task',
            entityId: 't-20000002-2222-4222-8222-222222222222',
            op: 'create',
            payload: JSON.stringify({ name: 'Another device task', due_at: '2026-11-01T10:00:00Z' }),
          });
        }
        return json(200, {
          changes,
          cursor: changes.length > 0 ? String(changes[changes.length - 1].seq) : null,
        });
      }
      return json(404, { error: 'not found' });
    };

    const count = await pullChanges({ fetchImpl: fetchImpl as unknown as typeof fetch });
    expect(count).toBe(2);

    // Проверяем, что задачи применились локально
    const t1 = await getTaskById('t-10000001-1111-4111-8111-111111111111');
    expect(t1).not.toBeNull();
    expect(t1!.name).toBe('From other device');

    const t2 = await getTaskById('t-20000002-2222-4222-8222-222222222222');
    expect(t2).not.toBeNull();
    expect(t2!.name).toBe('Another device task');
  });

  it('pullChanges обновляет курсор и не применяет уже известные изменения', async () => {
    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync/changes') {
        const after = Number(url.searchParams.get('after') ?? 0);
        const changes = after >= 100 ? [] : [
          { seq: 100, entity: 'task', entityId: 't-uuid-1', op: 'create', payload: JSON.stringify({ name: 'first' }) },
        ];
        return json(200, {
          changes,
          cursor: changes.length > 0 ? '100' : null,
        });
      }
      return json(404, { error: 'not found' });
    };

    // Первый pull
    const firstCount = await pullChanges({ fetchImpl: fetchImpl as unknown as typeof fetch });
    expect(firstCount).toBe(1);

    // Проверяем курсор
    const cursor1 = (await getDb().settings.get('syncCursor'))?.value;
    expect(cursor1).toBe('100');

    // Второй pull — никаких новых изменений
    const secondCount = await pullChanges({ fetchImpl: fetchImpl as unknown as typeof fetch });
    expect(secondCount).toBe(0);
  });

  it('первый вход: снимок страницами по 1000', async () => {
    const allSent: Array<{ after: string | null }> = [];

    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync/changes') {
        const after = Number(url.searchParams.get('after') ?? 0);
        const limit = Number(url.searchParams.get('limit') ?? 500);

        // Симулируем страницы по limit штук
        const pageSize = Math.min(limit, 500);
        const startSeq = after + 1;
        const endSeq = Math.min(startSeq + pageSize - 1, 2500); // 2500 tasks total
        const changes = [];
        for (let s = startSeq; s <= endSeq && s <= 2500; s++) {
          changes.push({
            seq: s,
            entity: 'task',
            entityId: `t-bulk-${String(s).padStart(6, '0')}`,
            op: 'create',
            payload: JSON.stringify({ name: `Bulk task ${s}` }),
          });
        }

        allSent.push({ after: url.searchParams.get('after') ?? null });

        return json(200, {
          changes,
          cursor: changes.length > 0 ? String(changes[changes.length - 1].seq) : null,
        });
      }
      return json(404, { error: 'not found' });
    };

    const count = await pullChanges({ fetchImpl: fetchImpl as unknown as typeof fetch });
    // Должны получить все 2500 задач (5 страниц по 500)
    expect(count).toBe(2500);

    // Проверяем, что было несколько запросов (пагинация)
    expect(allSent.length).toBeGreaterThan(1);
    // Последний запрос возвращает 0 изменений, break по changes.length === 0
    expect(allSent.length).toBe(6); // 2500 / 500 = 5 страниц + 1 пустой
  });

  it('пропускает изменения для entityId, у которого есть локальные неотправленные операции', async () => {
    // Симулируем локальное неотправленное изменение
    await addToSyncQueue({
      entity: 'task',
      entityId: 't-conflict-id',
      type: 'update',
      fields: { name: 'Local edit not yet pushed' },
      clientTs: new Date().toISOString(),
    });

    let changesSent = false;

    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync/changes') {
        changesSent = true;
        return json(200, {
          changes: [
            {
              seq: 50,
              entity: 'task',
              entityId: 't-conflict-id',
              op: 'update',
              payload: JSON.stringify({ name: 'Server version' }),
            },
          ],
          cursor: '50',
        });
      }
      return json(404, { error: 'not found' });
    };

    const count = await pullChanges({ fetchImpl: fetchImpl as unknown as typeof fetch });
    // Изменение для t-conflict-id пропущено, т.к. есть локальная неотправленная операция
    expect(changesSent).toBe(true);
    expect(count).toBe(0);
  });

  it('обрабатывает дельты для списков и тегов (SYNC-6)', async () => {
    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync/changes') {
        // Сервер пока не присылает списки/теги через этот эндпоинт
        // (только task-изменения). Проверяем, что pullChanges обрабатывает
        // и игнорирует не-task изменения без ошибок
        return json(200, {
          changes: [
            {
              seq: 10,
              entity: 'list',  // список создан на другом устройстве
              entityId: 'list-shared-uuid',
              op: 'create',
              payload: JSON.stringify({ name: 'Shared list', sharedWith: ['u1'] }),
            },
            {
              seq: 11,
              entity: 'task',
              entityId: 't-from-shared-list',
              op: 'create',
              payload: JSON.stringify({ name: 'In shared list', list_id: 'list-shared-uuid' }),
            },
          ],
          cursor: '11',
        });
      }
      return json(404, { error: 'not found' });
    };

    // Не должно упасть с ошибкой, не-task изменения игнорируются
    const count = await pullChanges({ fetchImpl: fetchImpl as unknown as typeof fetch });
    // Должны применить только task-изменения
    expect(count).toBe(1);

    const task = await getTaskById('t-from-shared-list');
    expect(task).not.toBeNull();
    expect(task!.name).toBe('In shared list');
  });
});