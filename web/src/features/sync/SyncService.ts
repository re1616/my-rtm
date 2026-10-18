// Taskly Web — Sync engine (push queued ops, pull server deltas).
//
// Push: ops leave the queue only after the server acknowledged them. A network failure keeps
// them pending (offline), a server-side rejection marks the op `failed` and it is retried a few
// times. Ops are sent strictly in queue order, in batches of up to 500 (SPEC F-5).
// Pull: deltas are fetched by cursor from /v1/sync/changes and applied to IndexedDB.
import { getDb } from '../../db/database';
import {
  getPendingSyncOps,
  markSyncOpStatus,
  removeSyncOps,
} from '../../entities/taskRepository';
import {
  ApiError,
  NetworkError,
  apiRequest,
  getSession,
  refreshSession,
  type ApiDeps,
} from '../../api/client';
import type { Session, SyncOperation, Task } from '../../types';

const SYNC_INTERVAL_MS = 15000;
const MAX_BATCH = 500;
const CURSOR_KEY = 'syncCursor';

export type SyncState = 'idle' | 'offline' | 'unauthenticated' | 'error';

export interface PushResult {
  sent: number;
  rejected: number;
  state: SyncState;
}

interface ServerResult {
  opId: string;
  status: string;
  entityId?: string;
  version?: number | null;
  error?: string | null;
}

/** Statuses after which retrying is pointless: the op is final on the server side. */
const FINAL_STATUSES = new Set(['ok', 'not_found', 'forbidden']);

/** Calls the API with the session token, refreshing it once on 401. */
async function authed<T>(
  deps: ApiDeps,
  session: Session,
  method: string,
  path: string,
  body?: unknown,
): Promise<{ result: T; session: Session }> {
  try {
    return { result: await apiRequest<T>(deps, method, path, body, session.accessToken), session };
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      const next = await refreshSession(deps, session);
      if (!next) throw e;
      return { result: await apiRequest<T>(deps, method, path, body, next.accessToken), session: next };
    }
    throw e;
  }
}

export async function pushPendingOps(deps: ApiDeps = {}): Promise<PushResult> {
  const stored = await getSession();
  if (!stored) return { sent: 0, rejected: 0, state: 'unauthenticated' };
  let session: Session = stored;

  let sent = 0;
  let rejected = 0;
  // Ops left "sending" by a crashed/closed tab were never confirmed — send them again
  // (the server deduplicates by opId).
  await getDb().syncQueue.where('status').equals('sending').modify({ status: 'pending' });

  // Each op is attempted at most once per cycle; rejected ones wait for the next cycle.
  const attempted = new Set<string>();
  for (;;) {
    const pending = (await getPendingSyncOps()).filter((op) => !attempted.has(op.opId)).slice(0, MAX_BATCH);
    if (pending.length === 0) break;
    pending.forEach((op) => attempted.add(op.opId));

    const scope = `user:${session.userId}`;
    const wireOps = pending.map((op) => toWire(op, scope));
    await getDb().syncQueue.bulkUpdate(pending.map((op) => ({ key: op.opId, changes: { status: 'sending' as const } })));

    let response: { results: ServerResult[] };
    try {
      const r: { result: { results: ServerResult[] }; session: Session } = await authed(
        deps,
        session,
        'POST',
        '/v1/sync',
        { ops: wireOps },
      );
      response = r.result;
      session = r.session;
    } catch (e) {
      // Nothing was confirmed: everything goes back to pending
      await getDb().syncQueue.bulkUpdate(pending.map((op) => ({ key: op.opId, changes: { status: 'pending' as const } })));
      if (e instanceof NetworkError) return { sent, rejected, state: 'offline' };
      if (e instanceof ApiError && e.status === 401) return { sent, rejected, state: 'unauthenticated' };
      return { sent, rejected, state: 'error' };
    }

    const byId = new Map(response.results.map((r) => [r.opId, r]));
    const done: string[] = [];
    for (const op of pending) {
      const r = byId.get(op.opId);
      if (r && FINAL_STATUSES.has(r.status)) {
        done.push(op.opId);
        if (r.status !== 'ok') rejected++;
      } else {
        await markSyncOpStatus(op.opId, 'failed', r?.error ?? 'no result from server');
        rejected++;
      }
    }
    await removeSyncOps(done);
    sent += done.length;
  }
  return { sent, rejected, state: 'idle' };
}

function toWire(op: SyncOperation, scope: string) {
  return {
    opId: op.opId,
    scope,
    entity: op.entity,
    entityId: op.entityId,
    type: op.type,
    fields: op.fields,
    ...(op.baseVersion != null ? { baseVersion: op.baseVersion } : {}),
    clientTs: op.clientTs,
  };
}

// ── Pull ──

interface ChangeEntry {
  seq: number;
  entity: string;
  entityId: string;
  op: string;
  payload?: string | null;
}

/** snake_case server payload → local task fields. */
function fromServerFields(p: Record<string, unknown>): Partial<Task> {
  const out: Partial<Task> = {};
  const has = (k: string) => Object.prototype.hasOwnProperty.call(p, k);
  const str = (v: unknown) => (v == null ? undefined : String(v));
  if (has('name')) out.name = String(p.name);
  if (has('list_id')) out.listId = str(p.list_id) ?? 'sys-inbox';
  if (has('priority')) out.priority = (p.priority as Task['priority']) ?? null;
  if (has('due_at')) out.dueDate = str(p.due_at);
  if (has('due_has_time')) out.dueHasTime = Boolean(p.due_has_time);
  if (has('start_at')) out.startDate = str(p.start_at);
  if (has('estimate_min')) out.estimateMin = (p.estimate_min as number | null) ?? undefined;
  if (has('rrule')) out.repeatRule = str(p.rrule);
  if (has('repeat_mode')) out.repeatMode = (str(p.repeat_mode) as Task['repeatMode']) ?? undefined;
  if (has('completed_at')) out.completedAt = str(p.completed_at);
  if (has('url')) out.url = str(p.url);
  return out;
}

export async function pullChanges(deps: ApiDeps = {}): Promise<number> {
  const stored = await getSession();
  if (!stored) return 0;
  let session: Session = stored;
  const db = getDb();
  let applied = 0;

  for (;;) {
    const cursor = (await db.settings.get(CURSOR_KEY))?.value;
    const scope = encodeURIComponent(`user:${session.userId}`);
    const path = `/v1/sync/changes?scope=${scope}&limit=500${cursor ? `&after=${cursor}` : ''}`;
    const r: { result: { changes: ChangeEntry[]; cursor?: string | null }; session: Session } =
      await authed(deps, session, 'GET', path);
    session = r.session;
    const { changes, cursor: next } = r.result;
    if (changes.length === 0) break;

    await db.transaction('rw', db.tasks, db.syncQueue, db.settings, async () => {
      // Local edits that are not pushed yet win until they reach the server
      const pendingIds = new Set((await db.syncQueue.toArray()).map((o) => o.entityId));
      for (const c of changes) {
        if (c.entity !== 'task' || pendingIds.has(c.entityId)) continue;
        await applyTaskChange(c);
        applied++;
      }
      if (next) await db.settings.put({ key: CURSOR_KEY, value: next });
    });
    if (changes.length < 500) break;
  }
  return applied;
}

async function applyTaskChange(c: ChangeEntry): Promise<void> {
  const db = getDb();
  let payload: Record<string, unknown> = {};
  try {
    payload = c.payload ? (JSON.parse(c.payload) as Record<string, unknown>) : {};
  } catch {
    /* keep empty */
  }
  const existing = await db.tasks.get(c.entityId);
  const now = new Date().toISOString();

  switch (c.op) {
    case 'create':
    case 'update':
    case 'move': {
      const fields = fromServerFields(payload);
      if (existing) {
        await db.tasks.update(c.entityId, { ...fields, updatedAt: now });
      } else if (c.op === 'create') {
        await db.tasks.put({
          id: c.entityId,
          listId: 'sys-inbox',
          name: '',
          priority: null,
          dueHasTime: false,
          postponedCount: 0,
          tags: [],
          version: 1,
          createdAt: now,
          updatedAt: now,
          ...fields,
        } as Task);
      }
      break;
    }
    case 'complete':
      if (existing) {
        await db.tasks.update(c.entityId, {
          completedAt: (payload.completed_at as string | undefined) ?? now,
          updatedAt: now,
        });
      }
      break;
    case 'uncomplete':
      if (existing) await db.tasks.update(c.entityId, { completedAt: undefined, updatedAt: now });
      break;
    case 'delete':
      if (existing) await db.tasks.update(c.entityId, { deletedAt: now, updatedAt: now });
      break;
    case 'tag_add':
    case 'tag_remove': {
      const tag = payload.tag as string | undefined;
      if (existing && tag) {
        const tags = new Set(existing.tags);
        if (c.op === 'tag_add') tags.add(tag);
        else tags.delete(tag);
        await db.tasks.update(c.entityId, { tags: [...tags] });
      }
      break;
    }
  }
}

/** Push, then pull. Never throws: returns the resulting state for the UI. */
export async function syncNow(deps: ApiDeps = {}): Promise<SyncState> {
  try {
    const push = await pushPendingOps(deps);
    if (push.state !== 'idle') return push.state;
    await pullChanges(deps);
    return 'idle';
  } catch (e) {
    if (e instanceof NetworkError) return 'offline';
    if (e instanceof ApiError && e.status === 401) return 'unauthenticated';
    return 'error';
  }
}

// ── Background loop ──

let syncTimer: ReturnType<typeof setInterval> | null = null;
let running: Promise<SyncState> | null = null;
let onState: ((s: SyncState) => void) | null = null;

/** Runs one sync cycle; concurrent calls share the same cycle. */
export function triggerSync(deps: ApiDeps = {}): Promise<SyncState> {
  if (!running) {
    running = syncNow(deps).finally(() => {
      running = null;
    });
    running.then((s) => onState?.(s));
  }
  return running;
}

function handleOnline() {
  void triggerSync();
}

export function startSyncService(listener?: (s: SyncState) => void): void {
  if (syncTimer) return;
  onState = listener ?? null;
  void triggerSync();
  syncTimer = setInterval(() => void triggerSync(), SYNC_INTERVAL_MS);
  if (typeof window !== 'undefined') window.addEventListener('online', handleOnline);
}

export function stopSyncService(): void {
  if (syncTimer) {
    clearInterval(syncTimer);
    syncTimer = null;
  }
  onState = null;
  if (typeof window !== 'undefined') window.removeEventListener('online', handleOnline);
}
