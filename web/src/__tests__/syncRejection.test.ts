// WEB-S2 / SYNC-1: refused ops are rolled back to the server's version and shown to the user;
// conflict_resolved ops are acknowledged and the local copy is aligned with the server.
import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { initializeDb, getDb } from '../db/database';
import { createTask, updateTask, getTaskById, getPendingSyncOps } from '../entities/taskRepository';
import { pushPendingOps, getRejections } from '../features/sync/SyncService';
import { setSession } from '../api/client';
import store from '../store/taskStore';

beforeAll(async () => {
  await initializeDb();
});

beforeEach(async () => {
  const db = getDb();
  await Promise.all([db.tasks.clear(), db.syncQueue.clear(), db.settings.clear()]);
  await setSession({ userId: 'u1', accessToken: 'token-1', refreshToken: 'r1' });
  store.setState({ rejections: [] });
});

type Handler = (url: URL, init?: RequestInit) => Response | undefined;
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** Fake server at the network boundary: answers /v1/sync with the given status per op. */
function server(statusFor: (op: { type: string; entityId: string }) => string, extra: Handler = () => undefined) {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://fake');
    const custom = extra(url, init);
    if (custom) return custom;
    if (url.pathname === '/v1/sync') {
      const { ops } = JSON.parse(String(init!.body)) as { ops: Array<{ opId: string; type: string; entityId: string }> };
      return json(200, {
        results: ops.map((op) => {
          const status = statusFor(op);
          return { opId: op.opId, status, entityId: op.entityId, error: status === 'ok' ? null : `server says ${status}` };
        }),
      });
    }
    return json(404, { error: 'not found' });
  }) as unknown as typeof fetch;
}

describe('refused ops are rolled back', () => {
  it('a refused update restores the server version and is reported', async () => {
    const task = await createTask({ name: 'Original' });
    await pushPendingOps({ fetchImpl: server(() => 'ok') });

    await updateTask(task.id, { name: 'Local edit' });
    const result = await pushPendingOps({
      fetchImpl: server(
        () => 'rejected',
        (url) =>
          url.pathname === `/v1/tasks/${task.id}`
            ? json(200, { id: task.id, name: 'Original', listId: 'sys-inbox', version: 3, createdAt: task.createdAt, updatedAt: task.updatedAt, tags: [] })
            : undefined,
      ),
    });

    expect(result.rejected).toBe(1);
    expect((await getTaskById(task.id))?.name).toBe('Original');
    expect(await getPendingSyncOps()).toHaveLength(0);
    const rejections = await getRejections();
    expect(rejections).toHaveLength(1);
    expect(rejections[0]).toMatchObject({ entity: 'task', entityId: task.id, reason: 'server says rejected' });
    expect(store.getState().rejections).toHaveLength(1);
  });

  it('a refused create removes the task the server does not have', async () => {
    const task = await createTask({ name: 'Never accepted' });
    await pushPendingOps({ fetchImpl: server(() => 'forbidden') });
    expect(await getTaskById(task.id)).toBeNull();
    expect((await getRejections())[0].entity).toBe('task');
  });

  it('transient errors are retried, then treated as refused after 5 attempts', async () => {
    const task = await createTask({ name: 'Flaky' });
    const fetchImpl = server(() => 'error');
    for (let i = 1; i <= 4; i++) {
      await pushPendingOps({ fetchImpl });
      expect(await getPendingSyncOps(), `attempt ${i} keeps the op`).toHaveLength(1);
    }
    await pushPendingOps({ fetchImpl });
    expect(await getPendingSyncOps()).toHaveLength(0);
    expect(await getTaskById(task.id)).toBeNull();
    expect(await getRejections()).toHaveLength(1);
  });

  it('conflict_resolved is acknowledged and the local copy takes the server values', async () => {
    const task = await createTask({ name: 'Mine' });
    await pushPendingOps({ fetchImpl: server(() => 'ok') });
    await updateTask(task.id, { name: 'Stale rename' });

    const result = await pushPendingOps({
      fetchImpl: server(
        () => 'conflict_resolved',
        (url) =>
          url.pathname === `/v1/tasks/${task.id}`
            ? json(200, { id: task.id, name: 'Newer on server', listId: 'sys-inbox', version: 5, createdAt: task.createdAt, updatedAt: task.updatedAt, tags: [] })
            : undefined,
      ),
    });

    expect(result.rejected).toBe(0);
    expect(result.sent).toBe(1);
    expect((await getTaskById(task.id))?.name).toBe('Newer on server');
    expect(await getRejections()).toHaveLength(0);
  });
});
