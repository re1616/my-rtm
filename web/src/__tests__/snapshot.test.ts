// WEB-S4: a device that never synced loads the account page by page, then continues from the
// change-log position the snapshot started at.
import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { initializeDb, getDb } from '../db/database';
import { addToSyncQueue } from '../entities/taskRepository';
import { syncNow } from '../features/sync/SyncService';
import { setSession } from '../api/client';

beforeAll(async () => {
  await initializeDb();
});

beforeEach(async () => {
  const db = getDb();
  await Promise.all([db.tasks.clear(), db.lists.clear(), db.tags.clear(), db.locations.clear(), db.syncQueue.clear(), db.settings.clear()]);
  await setSession({ userId: 'u1', accessToken: 'token-1', refreshToken: 'r1' });
});

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const serverTask = (n: number) => ({
  id: `t-${String(n).padStart(5, '0')}`,
  name: `Task ${n}`,
  listId: 'L1',
  priority: n % 3 === 0 ? 1 : null,
  tags: n === 1 ? ['tag-1'] : [],
  version: 1,
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
});

describe('WEB-S4: first-sync snapshot', () => {
  it('loads all pages of 1000, lists and tags, then pulls from the snapshot position', async () => {
    const total = 2300;
    const requests: string[] = [];
    let changesAfter: string | null = null;
    const fetchImpl = (async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://fake');
      requests.push(url.pathname + url.search);
      if (url.pathname === '/v1/sync/snapshot') {
        const cursor = url.searchParams.get('cursor');
        expect(url.searchParams.get('limit')).toBe('1000');
        const start = cursor ? Number(cursor.slice(2)) + 1 : 1;
        const end = Math.min(start + 999, total);
        const tasks = Array.from({ length: end - start + 1 }, (_, i) => serverTask(start + i));
        return json(200, {
          seq: cursor ? null : 777,
          tasks,
          lists: cursor ? [] : [{ id: 'L1', name: 'Работа', createdAt: '2026-10-01T10:00:00Z' }],
          tags: cursor ? [] : [{ id: 'tag-1', name: 'срочно' }],
          locations: cursor ? [] : [{ id: 'loc-1', name: 'Офис' }],
          nextCursor: end < total ? tasks[tasks.length - 1].id : null,
        });
      }
      if (url.pathname === '/v1/sync/changes') {
        changesAfter = url.searchParams.get('after');
        return json(200, { changes: [], cursor: null });
      }
      if (url.pathname === '/v1/sync') return json(200, { results: [] });
      return json(404, { error: 'not found' });
    }) as unknown as typeof fetch;

    expect(await syncNow({ fetchImpl })).toBe('idle');

    const db = getDb();
    expect(await db.tasks.count()).toBe(total);
    expect(requests.filter((r) => r.startsWith('/v1/sync/snapshot'))).toHaveLength(3);
    expect((await db.lists.get('L1'))?.name).toBe('Работа');
    expect((await db.locations.get('loc-1'))?.name).toBe('Офис');
    expect((await db.tasks.get('t-00001'))?.tags).toEqual(['срочно']); // tag ids → names
    expect(changesAfter).toBe('777');

    // A second sync does not reload the snapshot
    requests.length = 0;
    await syncNow({ fetchImpl });
    expect(requests.some((r) => r.startsWith('/v1/sync/snapshot'))).toBe(false);
  });

  it('keeps local edits that are not pushed yet', async () => {
    const db = getDb();
    await db.tasks.put({ ...serverTask(1), name: 'Local, unsent', listId: 'L1', priority: null, dueHasTime: false, postponedCount: 0, tags: [] });
    await addToSyncQueue({ entity: 'task', entityId: 't-00001', type: 'update', fields: { name: 'Local, unsent' }, clientTs: new Date().toISOString() });

    const fetchImpl = (async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://fake');
      // The push fails (offline) — the snapshot must not overwrite the queued edit either
      if (url.pathname === '/v1/sync') return json(200, { results: [] });
      if (url.pathname === '/v1/sync/snapshot') return json(200, { seq: 5, tasks: [serverTask(1)], lists: [], tags: [], locations: [], nextCursor: null });
      if (url.pathname === '/v1/sync/changes') return json(200, { changes: [], cursor: null });
      return json(404, {});
    }) as unknown as typeof fetch;

    await syncNow({ fetchImpl });
    expect((await db.tasks.get('t-00001'))?.name).toBe('Local, unsent');
  });
});

describe('server inbox is shown as the local "Входящие"', () => {
  it('tasks filed in the server Inbox stay in sys-inbox, and the Inbox list is not duplicated', async () => {
    const fetchImpl = (async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://fake');
      if (url.pathname === '/v1/sync') return json(200, { results: [] });
      if (url.pathname === '/v1/sync/snapshot') {
        return json(200, {
          seq: 3,
          tasks: [{ ...serverTask(1), listId: 'srv-inbox' }],
          lists: [{ id: 'srv-inbox', name: 'Inbox', createdAt: '2026-10-01T10:00:00Z' }],
          tags: [],
          locations: [],
          nextCursor: null,
        });
      }
      if (url.pathname === '/v1/sync/changes') {
        return json(200, {
          changes: [
            { seq: 4, entity: 'task', entityId: 't-new', op: 'create', payload: JSON.stringify({ name: 'From phone', list_id: 'srv-inbox' }) },
          ],
          cursor: '4',
        });
      }
      return json(404, {});
    }) as unknown as typeof fetch;

    await syncNow({ fetchImpl });
    const db = getDb();
    expect((await db.tasks.get('t-00001'))?.listId).toBe('sys-inbox');
    expect((await db.tasks.get('t-new'))?.listId).toBe('sys-inbox');
    expect(await db.lists.get('srv-inbox')).toBeUndefined();
  });
});
