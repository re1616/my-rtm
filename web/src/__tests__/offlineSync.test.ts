// Offline queue → server delivery (SPEC AC-7).
// A small in-memory fake of the Taskly sync API stands in for the server; the real server
// is exercised by offlineSync.e2e.test.ts in CI.
import { describe, it, expect, beforeEach } from 'vitest';
import { getDb, initializeDb } from '../db/database';
import {
  createTask,
  completeTask,
  updateTask,
  getPendingSyncOps,
  getTaskById,
  toWireFields,
} from '../entities/taskRepository';
import { pushPendingOps, pullChanges, syncNow } from '../features/sync/SyncService';
import { apiRequest, setSession } from '../api/client';

interface WireOp {
  opId: string;
  scope: string;
  entity: string;
  entityId: string;
  type: string;
  fields: Record<string, unknown>;
}

/** Minimal behaviour of the server's /v1/sync, /v1/sync/changes and /v1/tasks. */
class FakeServer {
  online = true;
  validToken = 'token-1';
  tasks = new Map<string, Record<string, unknown>>();
  processed = new Map<string, unknown>();
  received: WireOp[] = [];
  changes: Array<{ seq: number; entity: string; entityId: string; op: string; payload: string }> = [];
  rejectNames = new Set<string>();

  fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    if (!this.online) throw new TypeError('Failed to fetch');
    const url = new URL(String(input), 'http://fake');
    const auth = new Headers(init?.headers).get('Authorization');
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

    if (url.pathname === '/v1/auth/refresh') {
      return json(200, { accessToken: this.validToken, refreshToken: 'r2', userId: 'u1' });
    }
    if (auth !== `Bearer ${this.validToken}`) return json(401, { error: 'Invalid or expired access token' });

    if (url.pathname === '/v1/sync' && init?.method === 'POST') {
      const { ops } = JSON.parse(String(init.body)) as { ops: WireOp[] };
      const results = ops.map((op) => {
        const cached = this.processed.get(op.opId);
        if (cached) return cached;
        this.received.push(op);
        if (op.scope !== 'user:u1') return { opId: op.opId, status: 'forbidden' };
        if (typeof op.fields.name === 'string' && this.rejectNames.has(op.fields.name)) {
          return { opId: op.opId, status: 'error', error: 'rejected' };
        }
        const t = this.tasks.get(op.entityId) ?? {};
        if (op.type === 'create' || op.type === 'update') Object.assign(t, op.fields, { id: op.entityId });
        if (op.type === 'complete') t.completedAt = op.fields.completedAt ?? new Date().toISOString();
        if (op.type === 'delete') t.deletedAt = new Date().toISOString();
        this.tasks.set(op.entityId, t);
        const r = { opId: op.opId, status: 'ok', entityId: op.entityId, version: 1 };
        this.processed.set(op.opId, r);
        return r;
      });
      return json(200, { results });
    }
    if (url.pathname === '/v1/sync/changes') {
      const after = Number(url.searchParams.get('after') ?? 0);
      const changes = this.changes.filter((c) => c.seq > after);
      return json(200, { changes, cursor: changes.length ? String(changes[changes.length - 1].seq) : null });
    }
    if (url.pathname === '/v1/tasks') {
      const data = [...this.tasks.values()].filter((t) => !t.deletedAt);
      return json(200, { data });
    }
    return json(404, { error: 'not found' });
  };
}

let server: FakeServer;
const deps = () => ({ fetchImpl: server.fetch as typeof fetch, baseUrl: '' });

beforeEach(async () => {
  await initializeDb();
  const db = getDb();
  await Promise.all([db.tasks.clear(), db.syncQueue.clear(), db.settings.clear()]);
  server = new FakeServer();
  await setSession({ userId: 'u1', accessToken: 'token-1', refreshToken: 'r1' });
});

describe('offline queue (AC-7)', () => {
  it('a task created while the server is unreachable is delivered after recovery and visible in the API', async () => {
    server.online = false;
    const task = await createTask({ name: 'Купить молоко', priority: 1 });

    const offline = await pushPendingOps(deps());
    expect(offline.state).toBe('offline');
    expect((await getPendingSyncOps()).map((o) => o.type)).toEqual(['create']);
    expect(server.tasks.size).toBe(0);

    server.online = true;
    const online = await pushPendingOps(deps());
    expect(online).toEqual({ sent: 1, rejected: 0, state: 'idle' });
    expect(await getPendingSyncOps()).toHaveLength(0);

    const api = await apiRequest<{ data: Array<{ id: string; name: string; priority: number }> }>(
      deps(), 'GET', '/v1/tasks', undefined, 'token-1',
    );
    expect(api.data).toEqual([expect.objectContaining({ id: task.id, name: 'Купить молоко', priority: 1 })]);
  });

  it('sends queued ops strictly in the order they were made', async () => {
    server.online = false;
    const t = await createTask({ name: 'v1' });
    await updateTask(t.id, { name: 'v2' });
    await completeTask(t.id);
    await pushPendingOps(deps());

    server.online = true;
    await pushPendingOps(deps());
    expect(server.received.map((o) => o.type)).toEqual(['create', 'update', 'complete']);
    expect(server.tasks.get(t.id)).toMatchObject({ name: 'v2', completedAt: expect.any(String) });
  });

  it('keeps ops that the server rejected and retries them later', async () => {
    server.rejectNames.add('bad');
    await createTask({ name: 'bad' });
    await createTask({ name: 'good' });

    const r = await pushPendingOps(deps());
    expect(r.sent).toBe(1);
    const left = await getPendingSyncOps();
    expect(left).toHaveLength(1);
    expect(left[0]).toMatchObject({ status: 'failed', retryCount: 1, lastError: 'rejected' });
  });

  it('refreshes an expired access token once and continues', async () => {
    server.validToken = 'token-2';
    await createTask({ name: 'after refresh' });
    const r = await pushPendingOps(deps());
    expect(r.state).toBe('idle');
    expect(server.tasks.size).toBe(1);
  });

  it('without a session the queue is kept and nothing is sent', async () => {
    await setSession(null);
    await createTask({ name: 'local only' });
    expect(await syncNow(deps())).toBe('unauthenticated');
    expect(await getPendingSyncOps()).toHaveLength(1);
    expect(server.received).toHaveLength(0);
  });

  it('pulls changes made on another device and stores the cursor', async () => {
    server.changes.push({
      seq: 7,
      entity: 'task',
      entityId: '11111111-1111-4111-8111-111111111111',
      op: 'create',
      payload: JSON.stringify({ name: 'С телефона', priority: 2, due_at: '2026-10-20T09:00:00+03:00' }),
    });
    expect(await pullChanges(deps())).toBe(1);
    const t = await getTaskById('11111111-1111-4111-8111-111111111111');
    expect(t).toMatchObject({ name: 'С телефона', priority: 2, dueDate: '2026-10-20T09:00:00+03:00' });
    expect((await getDb().settings.get('syncCursor'))?.value).toBe('7');
    expect(await pullChanges(deps())).toBe(0);
  });
});

describe('wire format', () => {
  it('does not send local system views, keeps the inbox marker and turns dates into instants', () => {
    expect(toWireFields({ name: 'x', listId: 'sys-today' })).not.toHaveProperty('listId');
    const wire = toWireFields({ name: 'x', listId: 'sys-inbox', dueDate: '2026-10-20', priority: null });
    // "sys-inbox" is replaced with the server inbox id when the op is sent (see below)
    expect(wire.listId).toBe('sys-inbox');
    expect(wire.priority).toBeNull();
    expect(new Date(wire.dueAt as string).getDate()).toBe(20);
    expect(wire.dueAt).toMatch(/T/);
  });
});

describe('moving a task back to Inbox (regression)', () => {
  it('is sent with the server inbox id; a new inbox task is sent without a list', async () => {
    const db = getDb();
    await db.settings.bulkPut([
      { key: 'syncCursor', value: '1' },
      { key: 'serverInboxId', value: 'srv-inbox' },
    ]);
    const work = '22222222-2222-4222-8222-222222222222';
    const inInbox = await createTask({ name: 'inbox task' });
    const moved = await createTask({ name: 'work task', listId: work });
    await updateTask(moved.id, { listId: 'sys-inbox' });

    expect(await syncNow(deps())).toBe('idle');
    const sent = server.received;
    expect(sent.find((o) => o.entityId === inInbox.id && o.type === 'create')?.fields.listId).toBe('srv-inbox');
    expect(sent.find((o) => o.entityId === moved.id && o.type === 'create')?.fields.listId).toBe(work);
    const move = sent.find((o) => o.entityId === moved.id && o.type !== 'create');
    expect(move).toMatchObject({ type: 'move', fields: { listId: 'srv-inbox' } });
  });
});
