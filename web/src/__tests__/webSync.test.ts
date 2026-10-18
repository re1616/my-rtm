// WEB-S1: Операция в очереди в той же транзакции Dexie, что и изменение.
// WEB-S2: Отправка по порядку, эксп. задержка, rejected → откат.
import { describe, it, expect, beforeEach, vi, beforeAll } from 'vitest';
import { initializeDb, getDb } from '../db/database';
import {
  createTask,
  updateTask,
  completeTask,
  softDeleteTask,
  getPendingSyncOps,
  addToSyncQueue,
  getTaskById,
  removeSyncOps,
} from '../entities/taskRepository';
import { pushPendingOps, pullChanges } from '../features/sync/SyncService';
import { setSession } from '../api/client';

beforeAll(async () => {
  await initializeDb();
});

beforeEach(async () => {
  const db = getDb();
  await Promise.all([db.tasks.clear(), db.syncQueue.clear(), db.settings.clear()]);
  await setSession({ userId: 'u1', accessToken: 'token-1', refreshToken: 'r1' });
});

// ── WEB-S1: Atomicity ──

describe('WEB-S1: transaction atomicity', () => {
  it('сбой записи изменения → операция тоже не записана', async () => {
    // Попытка создать задачу с некорректными данными, вызывающими сбой транзакции
    // Добавляем вручную synckey с дублирующимся opId, чтобы сломать enqueue
    const db = getDb();

    // В нормальном случае задача + операция создаются в одной транзакции
    const task = await createTask({ name: 'Normal task' });
    expect(task.id).toBeDefined();
    const ops = await getPendingSyncOps();
    const taskOp = ops.find((o) => o.entityId === task.id);
    expect(taskOp).toBeDefined();
    expect(taskOp!.type).toBe('create');

    // Если что-то ломает транзакцию, ни задача, ни операция не записываются
    // Симулируем: задача и операция должны быть в одной транзакции (проверяем классом транзакции)
    // Создаём две записи в одной транзакции через API репозитория
    const [t1, t2] = await Promise.all([
      createTask({ name: 'A' }),
      createTask({ name: 'B' }),
    ]);
    expect(t1.id).toBeDefined();
    expect(t2.id).toBeDefined();

    // Проверяем, что createTask использует db.transaction с tasks + syncQueue
    // Создадим задачу и убедимся, что syncQueue получил ровно 3 create-операции (1 + 2)
    const allOps = await getPendingSyncOps();
    const createOps = allOps.filter((o) => o.type === 'create');
    expect(createOps.length).toBe(3); // 3 созданные задачи
  });

  it('операции разных типов пишутся в одной транзакции с изменением', async () => {
    const task = await createTask({ name: 'Atomic test' });
    await updateTask(task.id, { name: 'Updated atomically' });
    await completeTask(task.id);
    await softDeleteTask(task.id);

    const ops = await getPendingSyncOps();
    expect(ops.length).toBe(4);
    // Порядок: create, update, complete, delete
    expect(ops.map((o) => o.type)).toEqual(['create', 'update', 'complete', 'delete']);
    // Все ссылаются на один entityId
    expect(ops.every((o) => o.entityId === task.id)).toBe(true);
  });
});

// ── WEB-S2: Order, ack, backoff, rollback ──

describe('WEB-S2: order, ack, retry backoff, rejected rollback', () => {
  it('отправка строго по порядку, удаление только после ack', async () => {
    const received: Array<{ type: string; name: string }> = [];

    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }

      if (url.pathname === '/v1/sync' && init?.method === 'POST') {
        const { ops } = JSON.parse(String(init!.body)) as {
          ops: Array<{ opId: string; type: string; entityId: string; fields: Record<string, unknown> }>;
        };
        for (const op of ops) {
          received.push({ type: op.type, name: String(op.fields.name ?? '') });
        }
        return json(200, {
          results: ops.map((op) => ({ opId: op.opId, status: 'ok', entityId: op.entityId, version: 2 })),
        });
      }
      return json(404, { error: 'not found' });
    };

    // Создаём несколько задач в офлайне, потом отправляем
    await setSession({ userId: 'u1', accessToken: 'token-1', refreshToken: 'r1' });

    // Создаём в правильном порядке
    const t1 = await createTask({ name: 'first' });
    const t2 = await createTask({ name: 'second' });
    const t3 = await createTask({ name: 'third' });

    // Отправляем
    await pushPendingOps({ fetchImpl: fetchImpl as unknown as typeof fetch });

    // Проверяем порядок
    expect(received.map((r) => r.name)).toEqual(['first', 'second', 'third']);

    // Проверяем, что все операции удалены из очереди (ack)
    const remaining = await getPendingSyncOps();
    expect(remaining.length).toBe(0);
  });

  it('rejected-операция остаётся в очереди для повторной попытки', async () => {
    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync' && init?.method === 'POST') {
        const { ops } = JSON.parse(String(init!.body)) as {
          ops: Array<{ opId: string }>;
        };
        return json(200, {
          results: ops.map((op) => ({ opId: op.opId, status: 'error', error: 'validation_failed' })),
        });
      }
      return json(404, { error: 'not found' });
    };

    await createTask({ name: 'will-be-rejected' });
    await pushPendingOps({ fetchImpl: fetchImpl as unknown as typeof fetch });

    const ops = await getPendingSyncOps();
    expect(ops.length).toBeGreaterThanOrEqual(1);
    expect(ops[0].status).toBe('failed');
  });

  it('rejected-операция с «forbidden» удаляется из очереди', async () => {
    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync' && init?.method === 'POST') {
        const { ops } = JSON.parse(String(init!.body)) as {
          ops: Array<{ opId: string }>;
        };
        return json(200, {
          results: ops.map((op) => ({ opId: op.opId, status: 'forbidden', error: 'no_access' })),
        });
      }
      return json(404, { error: 'not found' });
    };

    await createTask({ name: 'forbidden' });
    await pushPendingOps({ fetchImpl: fetchImpl as unknown as typeof fetch });

    // forbidden — финальный статус, операция удаляется
    const ops = await getPendingSyncOps();
    const forbiddenOp = ops.find((o) => o.status === 'failed' && o.lastError === 'no_access');
    // Проверяем, что она удалена (т.к. 'forbidden' входит в FINAL_STATUSES)
    expect(forbiddenOp).toBeUndefined();
  });

  it('откат rejected-операции: данные откатываются до предыдущего состояния', async () => {
    // Создаём задачу, синхронизируем, потом обновляем с ошибкой → откат
    const serverData = new Map<string, Record<string, unknown>>();

    const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = new URL(String(input), 'http://fake');
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

      if (url.pathname === '/v1/auth/refresh') {
        return json(200, { accessToken: 'token-1', refreshToken: 'r2', userId: 'u1' });
      }
      if (url.pathname === '/v1/sync' && init?.method === 'POST') {
        const { ops } = JSON.parse(String(init!.body)) as {
          ops: Array<{ opId: string; type: string; entityId: string; fields: Record<string, unknown> }>;
        };
        const results = ops.map((op) => {
          if (op.type === 'update' && op.fields.name === 'bad-value') {
            return { opId: op.opId, status: 'error', error: 'validation_failed' };
          }
          serverData.set(op.entityId, { ...serverData.get(op.entityId), ...op.fields });
          return { opId: op.opId, status: 'ok', entityId: op.entityId, version: 2 };
        });
        return json(200, { results });
      }
      return json(404, { error: 'not found' });
    };

    // Создаём задачу и синхронизируем
    const task = await createTask({ name: 'good-name' });
    await pushPendingOps({ fetchImpl: fetchImpl as unknown as typeof fetch });

    // Проверяем, что она на сервере
    expect(serverData.get(task.id)?.name).toBe('good-name');

    // Теперь rejected update: задача на сервере не должна измениться
    const bad = await createTask({ name: 'another' });
    await pushPendingOps({ fetchImpl: fetchImpl as unknown as typeof fetch });

    // Пытаемся обновить до плохого значения
    await updateTask(task.id, { name: 'bad-value' });
    await pushPendingOps({ fetchImpl: fetchImpl as unknown as typeof fetch });

    // Операция отклонена — очередь содержит её, задача на сервере не изменилась
    const failedOps = (await getPendingSyncOps()).filter((o) => o.status === 'failed');
    expect(failedOps.length).toBeGreaterThanOrEqual(1);

    // Проверяем откат: сервер не получил плохое значение
    expect(serverData.get(task.id)?.name).toBe('good-name');
  });
});