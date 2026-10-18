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
import type { Session, SyncOperation, Task, TaskList, TaskTag, TaskLocation, TaskNote, TaskAttachment } from '../../types';
import store from '../../store/taskStore';
import { backoffDelay } from './backoff';
import { SyncLeadership, type SyncMessage } from './leader';
import { connectRealtime, realtimeUrl } from './realtime';
import { defaultBaseUrl } from '../../api/client';

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

/** Applied by the server. `conflict_resolved` = applied, but some fields lost to newer values. */
const APPLIED_STATUSES = new Set(['ok', 'conflict_resolved']);
/** Final refusals: the same op will never succeed, so the local change is rolled back. */
const REFUSED_STATUSES = new Set(['rejected', 'forbidden', 'not_found']);
/** Transient failures are retried this many times before the op is treated as refused. */
const MAX_ATTEMPTS = 5;
const REJECTIONS_KEY = 'syncRejections';

/** A local change the server refused; shown to the user (WEB-S2). */
export interface SyncRejection {
  entity: string;
  entityId: string;
  type: string;
  reason: string;
  at: string;
}

/** Calls the API with the session token, refreshing it once on 401.
 *  SYNC-7: передаёт Taskly-Sync-Version в каждом sync-запросе.
 */
async function authed<T>(
  deps: ApiDeps,
  session: Session,
  method: string,
  path: string,
  body?: unknown,
  extraHeaders?: Record<string, string>,
): Promise<{ result: T; session: Session }> {
  const syncHeaders = { ...(extraHeaders ?? {}), 'Taskly-Sync-Version': '2' };
  try {
    return { result: await apiRequest<T>(deps, method, path, body, session.accessToken, syncHeaders), session };
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      const next = await refreshSession(deps, session);
      if (!next) throw e;
      return { result: await apiRequest<T>(deps, method, path, body, next.accessToken, syncHeaders), session: next };
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
    const toAlign: SyncOperation[] = [];
    const refusals: SyncRejection[] = [];
    for (const op of pending) {
      const r = byId.get(op.opId);
      if (r && APPLIED_STATUSES.has(r.status)) {
        done.push(op.opId);
        // Fields the server kept (LWW) never come back as a change: fetch the server's version
        if (r.status === 'conflict_resolved') toAlign.push(op);
      } else if (r && REFUSED_STATUSES.has(r.status)) {
        done.push(op.opId);
        toAlign.push(op);
        refusals.push(refusal(op, r.error ?? r.status));
      } else if ((op.retryCount ?? 0) + 1 >= MAX_ATTEMPTS) {
        done.push(op.opId);
        toAlign.push(op);
        refusals.push(refusal(op, r?.error ?? 'no result from server'));
      } else {
        await markSyncOpStatus(op.opId, 'failed', r?.error ?? 'no result from server');
      }
    }
    await removeSyncOps(done);
    sent += done.length - refusals.length;
    rejected += refusals.length;
    if (refusals.length) await recordRejections(refusals);
    let changed = false;
    for (const op of toAlign) changed = (await alignWithServer(deps, session, op)) || changed;
    if (changed) notifyDataChanged();
  }
  return { sent, rejected, state: 'idle' };
}

function refusal(op: SyncOperation, reason: string): SyncRejection {
  return { entity: op.entity, entityId: op.entityId, type: op.type, reason, at: new Date().toISOString() };
}

export async function getRejections(): Promise<SyncRejection[]> {
  const row = await getDb().settings.get(REJECTIONS_KEY);
  try {
    return row ? (JSON.parse(row.value) as SyncRejection[]) : [];
  } catch {
    return [];
  }
}

export async function clearRejections(): Promise<void> {
  await getDb().settings.delete(REJECTIONS_KEY);
  store.getState().setRejections([]);
}

async function recordRejections(items: SyncRejection[]): Promise<void> {
  const all = [...items, ...(await getRejections())].slice(0, 50);
  await getDb().settings.put({ key: REJECTIONS_KEY, value: JSON.stringify(all) });
  store.getState().setRejections(all);
}

/**
 * Rolls a local entity back to the server's truth after a refused op (or a partial LWW
 * merge): re-fetches it, or removes it when the server does not have it. Skipped while
 * newer local edits for the same entity are still queued — they will be sent first.
 * Returns true when local data changed.
 */
async function alignWithServer(deps: ApiDeps, session: Session, op: SyncOperation): Promise<boolean> {
  const db = getDb();
  if ((await db.syncQueue.where('entityId').equals(op.entityId).count()) > 0) return false;
  const path = op.entity === 'task' ? `/v1/tasks/${op.entityId}` : op.entity === 'list' ? `/v1/lists/${op.entityId}` : null;
  try {
    if (!path) {
      // Tags/locations have no single-item endpoint: a refused create never existed on the server
      if (op.type === 'create') {
        if (op.entity === 'tag') await db.tags.delete(op.entityId);
        if (op.entity === 'location') await db.locations.delete(op.entityId);
        if (op.entity === 'note') await db.notes.delete(op.entityId);
        if (op.entity === 'attachment') await db.attachments.delete(op.entityId);
        return true;
      }
      return false;
    }
    const { result } = await authed<Record<string, unknown>>(deps, session, 'GET', path);
    if (op.entity === 'task') {
      const local = await db.tasks.get(op.entityId);
      await db.tasks.put(await taskFromResponse(result as unknown as TaskResponse, local));
    } else {
      const local = await db.lists.get(op.entityId);
      await db.lists.put(listFromResponse(result as unknown as ListResponse, local));
    }
    return true;
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 403)) {
      if (op.entity === 'task') await db.tasks.delete(op.entityId);
      if (op.entity === 'list') await db.lists.delete(op.entityId);
      return true;
    }
    return false; // offline or server error: the local copy stays until the next change from the server
  }
}

function toWire(op: SyncOperation, scope: string) {
  let fields = op.fields;
  // "sys-inbox" is the local marker for the server inbox (see taskRepository.wireListId)
  if (op.entity === 'task' && fields?.listId === 'sys-inbox') {
    fields = { ...fields };
    if (serverInboxId) fields.listId = serverInboxId;
    else delete fields.listId; // a create falls back to the server inbox by itself
  }
  return {
    opId: op.opId,
    scope,
    entity: op.entity,
    entityId: op.entityId,
    type: op.type,
    fields,
    ...(op.baseVersion != null ? { baseVersion: op.baseVersion } : {}),
    clientTs: op.clientTs,
  };
}

// ── Server inbox ↔ local "Входящие" ──
//
// The server files tasks without a list into a per-user list named "Inbox"; locally that is
// the system list `sys-inbox`. Server data is mapped on the way in, so synced tasks stay in
// "Входящие" instead of moving to a separate "Inbox" list.

const SERVER_INBOX_KEY = 'serverInboxId';
const SERVER_INBOX_NAME = 'Inbox';
let serverInboxId: string | null = null;

async function loadServerInbox(): Promise<void> {
  serverInboxId = (await getDb().settings.get(SERVER_INBOX_KEY))?.value ?? null;
}

/** Remembers the server inbox and moves anything already filed under it to `sys-inbox`. */
async function adoptServerInbox(id: string): Promise<void> {
  if (serverInboxId === id) return;
  serverInboxId = id;
  const db = getDb();
  await db.settings.put({ key: SERVER_INBOX_KEY, value: id });
  await db.tasks.where('listId').equals(id).modify({ listId: 'sys-inbox' });
  await db.lists.delete(id);
}

function localListId(serverId: string): string {
  return serverId === serverInboxId ? 'sys-inbox' : serverId;
}

/** Devices that synced before the mapping existed learn the inbox id once from /v1/lists. */
async function resolveServerInbox(deps: ApiDeps, session: Session): Promise<void> {
  if (serverInboxId) return;
  try {
    const { result } = await authed<Array<{ id: string; name: string }>>(deps, session, 'GET', '/v1/lists');
    const inbox = result.find((l) => l.name === SERVER_INBOX_NAME);
    if (inbox) await adoptServerInbox(inbox.id);
  } catch {
    // Not critical for this cycle: tried again on the next sync
  }
}

// ── Snapshot (first sync on this device, WEB-S4) ──

/** Server task as returned by the REST API and the snapshot (tags are tag ids). */
interface TaskResponse {
  id: string;
  name: string;
  listId: string;
  priority?: number | null;
  dueAt?: string | null;
  dueHasTime?: boolean;
  startAt?: string | null;
  estimateMin?: number | null;
  rrule?: string | null;
  repeatMode?: string | null;
  completedAt?: string | null;
  url?: string | null;
  parentId?: string | null;
  locationId?: string | null;
  postponedCount?: number | null;
  tags?: string[] | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

interface ListResponse {
  id: string;
  name: string;
  isSmart?: boolean;
  query?: string | null;
  color?: string | null;
  sortOrder?: number;
  version?: number;
  createdAt: string;
}

interface SnapshotPage {
  seq?: number | null;
  tasks: TaskResponse[];
  lists: ListResponse[];
  tags: { id: string; name: string; color?: string | null }[];
  locations: { id: string; name: string }[];
  notes?: { id: string; taskId: string; content: string; createdAt: string; updatedAt: string }[];
  attachments?: { id: string; taskId: string; name: string; url: string; createdAt: string }[];
  nextCursor?: string | null;
}

const SNAPSHOT_PAGE = 1000;

async function taskFromResponse(t: TaskResponse, local?: Task): Promise<Task> {
  const db = getDb();
  const tagNames: string[] = [];
  for (const id of t.tags ?? []) {
    const tag = await db.tags.get(id);
    if (tag) tagNames.push(tag.name);
  }
  return {
    ...(local ?? {}),
    id: t.id,
    listId: localListId(t.listId),
    name: t.name,
    priority: (t.priority ?? null) as Task['priority'],
    dueDate: t.dueAt ?? undefined,
    dueHasTime: Boolean(t.dueHasTime),
    startDate: t.startAt ?? undefined,
    estimateMin: t.estimateMin ?? undefined,
    repeatRule: t.rrule ?? undefined,
    repeatMode: (t.repeatMode ?? undefined) as Task['repeatMode'],
    completedAt: t.completedAt ?? undefined,
    url: t.url ?? undefined,
    parentId: t.parentId ?? undefined,
    locationId: t.locationId ?? undefined,
    tags: tagNames,
    postponedCount: t.postponedCount ?? local?.postponedCount ?? 0,
    version: t.version,
    createdAt: t.createdAt,
    updatedAt: t.updatedAt,
  };
}

function listFromResponse(l: ListResponse, local?: TaskList): TaskList {
  return {
    ...(local ?? {}),
    id: l.id,
    name: l.name,
    isSystem: false,
    isSmart: Boolean(l.isSmart),
    filterQuery: l.query ?? undefined,
    color: l.color ?? undefined,
    sortOrder: l.sortOrder ?? 0,
    archived: local?.archived ?? false,
    createdAt: l.createdAt,
    updatedAt: local?.updatedAt ?? l.createdAt,
  };
}

/**
 * Loads the account's data page by page on a device that has never synced, then stores the
 * change-log position so pulls continue from there. Entities with unsent local edits keep the
 * local version. [onProgress] gets the number of tasks loaded so far.
 */
export async function loadSnapshot(deps: ApiDeps = {}, onProgress?: (loaded: number) => void): Promise<number> {
  const stored = await getSession();
  if (!stored) return 0;
  let session: Session = stored;
  const db = getDb();
  let cursor: string | null = null;
  let seq: number | null = null;
  let loaded = 0;
  for (;;) {
    const path = `/v1/sync/snapshot?limit=${SNAPSHOT_PAGE}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
    const r: { result: SnapshotPage; session: Session } = await authed(deps, session, 'GET', path);
    session = r.session;
    const page = r.result;
    if (seq === null) seq = page.seq ?? 0;
    const pending = new Set((await db.syncQueue.toArray()).map((o) => o.entityId));
    await db.transaction('rw', [db.tasks, db.lists, db.tags, db.locations, db.notes, db.attachments, db.settings], async () => {
      // Tags first: tasks reference them by id
      for (const tag of page.tags) {
        if (!pending.has(tag.id)) await db.tags.put({ id: tag.id, name: tag.name, color: tag.color ?? undefined, createdAt: new Date().toISOString() });
      }
      for (const loc of page.locations) {
        if (!pending.has(loc.id)) await db.locations.put({ id: loc.id, name: loc.name, createdAt: new Date().toISOString() });
      }
      for (const list of page.lists) {
        if (list.name === SERVER_INBOX_NAME) await adoptServerInbox(list.id);
        else if (!pending.has(list.id)) await db.lists.put(listFromResponse(list, await db.lists.get(list.id)));
      }
      for (const t of page.tasks) {
        if (!pending.has(t.id)) await db.tasks.put(await taskFromResponse(t, await db.tasks.get(t.id)));
      }
      for (const n of page.notes ?? []) {
        if (!pending.has(n.id)) await db.notes.put({ id: n.id, taskId: n.taskId, content: n.content, createdAt: n.createdAt, updatedAt: n.updatedAt });
      }
      for (const a of page.attachments ?? []) {
        if (!pending.has(a.id)) await db.attachments.put({ id: a.id, taskId: a.taskId, name: a.name, url: a.url, createdAt: a.createdAt });
      }
    });
    loaded += page.tasks.length;
    onProgress?.(loaded);
    if (!page.nextCursor) break;
    cursor = page.nextCursor;
  }
  await db.settings.put({ key: CURSOR_KEY, value: String(seq ?? 0) });
  notifyDataChanged();
  return loaded;
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
  if (has('list_id')) out.listId = localListId(str(p.list_id) ?? 'sys-inbox');
  if (has('priority')) out.priority = (p.priority as Task['priority']) ?? null;
  if (has('due_at')) out.dueDate = str(p.due_at);
  if (has('due_has_time')) out.dueHasTime = Boolean(p.due_has_time);
  if (has('start_at')) out.startDate = str(p.start_at);
  if (has('estimate_min')) out.estimateMin = (p.estimate_min as number | null) ?? undefined;
  if (has('rrule')) out.repeatRule = str(p.rrule);
  if (has('repeat_mode')) out.repeatMode = (str(p.repeat_mode) as Task['repeatMode']) ?? undefined;
  if (has('completed_at')) out.completedAt = str(p.completed_at);
  if (has('url')) out.url = str(p.url);
  if (has('parent_id')) out.parentId = str(p.parent_id);
  if (has('location_id')) out.locationId = str(p.location_id);
  if (has('postponed_count')) out.postponedCount = Number(p.postponed_count ?? 0);
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

    await db.transaction(
      'rw',
      [db.tasks, db.lists, db.tags, db.locations, db.notes, db.attachments, db.syncQueue, db.settings],
      async () => {
      // Local edits that are not pushed yet win until they reach the server
      const pendingIds = new Set((await db.syncQueue.toArray()).map((o) => o.entityId));
      for (const c of changes) {
        if (pendingIds.has(c.entityId)) continue;
        switch (c.entity) {
          case 'task':
            await applyTaskChange(c);
            applied++;
            break;
          case 'list':
            await applyListChange(c);
            applied++;
            break;
          case 'tag':
            await applyTagChange(c);
            applied++;
            break;
          case 'location':
            await applyLocationChange(c);
            applied++;
            break;
          case 'note':
            await applyNoteChange(c);
            applied++;
            break;
          case 'attachment':
            await applyAttachmentChange(c);
            applied++;
            break;
          // unknown entity — skip silently (forward compat)
        }
      }
      if (next) await db.settings.put({ key: CURSOR_KEY, value: next });
    });
    if (changes.length < 500) break;
  }
  if (applied > 0) notifyDataChanged();
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
    case 'restore': {
      const fields = fromServerFields(payload);
      if (existing) {
        await db.tasks.update(c.entityId, { ...fields, deletedAt: undefined, updatedAt: now });
      } else {
        await db.tasks.put({
          id: c.entityId, listId: 'sys-inbox', name: '', priority: null, dueHasTime: false,
          postponedCount: 0, tags: [], version: 1, createdAt: now, updatedAt: now, ...fields,
        } as Task);
      }
      break;
    }
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

/**
 * Применить дельту списка (SYNC-6: списки, теги, локации из дельт scope).
 * Формат payload сервера: { name: string, isSmart?: boolean, filterQuery?: string, sortOrder?: int, archived?: boolean }
 */
async function applyListChange(c: ChangeEntry): Promise<void> {
  const db = getDb();
  if (c.entityId === serverInboxId) return; // shown as the local "Входящие"
  let payload: Record<string, unknown> = {};
  try {
    payload = c.payload ? (JSON.parse(c.payload) as Record<string, unknown>) : {};
  } catch { /* keep empty */ }
  const now = new Date().toISOString();
  const has = (k: string) => Object.prototype.hasOwnProperty.call(payload, k);
  const str = (v: unknown) => (v == null ? undefined : String(v));

  switch (c.op) {
    case 'create':
    case 'update': {
      const existing = await db.lists.get(c.entityId);
      // Payload keys as the writer sent them: camelCase (clients) or snake_case (REST)
      const pick = (...keys: string[]) => keys.find((k) => has(k));
      const update: Record<string, unknown> = { updatedAt: now };
      if (has('name')) update.name = String(payload.name);
      const sortKey = pick('sortOrder', 'sort_order');
      if (sortKey) update.sortOrder = Number(payload[sortKey] ?? 0);
      if (has('archived')) update.archived = payload.archived === true || payload.archived === 'true';
      const smartKey = pick('isSmart', 'is_smart');
      if (smartKey) update.isSmart = payload[smartKey] === true || payload[smartKey] === 'true';
      if (has('query')) update.filterQuery = str(payload.query);
      if (has('color')) update.color = str(payload.color);
      if (existing) {
        await db.lists.update(c.entityId, update);
      } else if (c.op === 'create') {
        await db.lists.put({
          id: c.entityId,
          name: '',
          isSystem: false,
          sortOrder: 0,
          archived: false,
          createdAt: now,
          ...update,
        } as TaskList);
      }
      break;
    }
    case 'delete': {
      const existing = await db.lists.get(c.entityId);
      if (existing) await db.lists.delete(c.entityId);
      break;
    }
  }
}

/**
 * Применить дельту тега.
 * Формат payload сервера: { name: string, color?: string }
 */
async function applyTagChange(c: ChangeEntry): Promise<void> {
  const db = getDb();
  let payload: Record<string, unknown> = {};
  try {
    payload = c.payload ? (JSON.parse(c.payload) as Record<string, unknown>) : {};
  } catch { /* keep empty */ }
  const now = new Date().toISOString();

  switch (c.op) {
    case 'create':
    case 'update': {
      const existing = await db.tags.get(c.entityId);
      if (existing) {
        const update: Record<string, unknown> = { updatedAt: now };
        if (Object.prototype.hasOwnProperty.call(payload, 'name')) update.name = String(payload.name);
        if (Object.prototype.hasOwnProperty.call(payload, 'color')) update.color = String(payload.color);
        await db.tags.update(c.entityId, update);
      } else if (c.op === 'create') {
        await db.tags.put({
          id: c.entityId,
          name: String(payload.name ?? ''),
          color: payload.color ? String(payload.color) : undefined,
          createdAt: now,
        } as TaskTag);
      }
      break;
    }
    case 'delete': {
      const existing = await db.tags.get(c.entityId);
      if (existing) await db.tags.delete(c.entityId);
      break;
    }
  }
}

/**
 * Применить дельту локации.
 * Формат payload сервера: { name: string }
 * На вебе локации хранятся в отдельной таблице locations.
 */
async function applyLocationChange(c: ChangeEntry): Promise<void> {
  const db = getDb();
  let payload: Record<string, unknown> = {};
  try {
    payload = c.payload ? (JSON.parse(c.payload) as Record<string, unknown>) : {};
  } catch { /* keep empty */ }
  const now = new Date().toISOString();

  switch (c.op) {
    case 'create':
    case 'update': {
      const existing = await db.locations.get(c.entityId);
      if (existing) {
        const update: Record<string, unknown> = { updatedAt: now };
        if (Object.prototype.hasOwnProperty.call(payload, 'name')) update.name = String(payload.name);
        await db.locations.update(c.entityId, update);
      } else if (c.op === 'create') {
        await db.locations.put({
          id: c.entityId,
          name: String(payload.name ?? ''),
          createdAt: now,
          updatedAt: now,
        } as TaskLocation);
      }
      break;
    }
    case 'delete': {
      const existing = await db.locations.get(c.entityId);
      if (existing) await db.locations.delete(c.entityId);
      break;
    }
  }
}

function parsePayload(c: ChangeEntry): Record<string, unknown> {
  try {
    return c.payload ? (JSON.parse(c.payload) as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

/** Notes of the task card: { task_id, content } (WEB-F3). */
async function applyNoteChange(c: ChangeEntry): Promise<void> {
  const db = getDb();
  const p = parsePayload(c);
  const now = new Date().toISOString();
  if (c.op === 'delete') {
    await db.notes.delete(c.entityId);
    return;
  }
  const existing = await db.notes.get(c.entityId);
  const note: TaskNote = {
    id: c.entityId,
    taskId: String(p.task_id ?? existing?.taskId ?? ''),
    content: String(p.content ?? existing?.content ?? ''),
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  };
  if (note.taskId) await db.notes.put(note);
}

/** Link attachments: { task_id, name, url }. */
async function applyAttachmentChange(c: ChangeEntry): Promise<void> {
  const db = getDb();
  if (c.op === 'delete') {
    await db.attachments.delete(c.entityId);
    return;
  }
  const p = parsePayload(c);
  const att: TaskAttachment = {
    id: c.entityId,
    taskId: String(p.task_id ?? ''),
    name: String(p.name ?? p.url ?? ''),
    url: String(p.url ?? ''),
    createdAt: new Date().toISOString(),
  };
  if (att.taskId && att.url) await db.attachments.put(att);
}

/** Push, then pull. Never throws: returns the resulting state for the UI. */
export async function syncNow(deps: ApiDeps = {}): Promise<SyncState> {
  try {
    await loadServerInbox();
    // The inbox id is needed to send "move to Inbox" (see toWire)
    const signedIn = await getSession();
    if (signedIn && (await getDb().settings.get(CURSOR_KEY))) await resolveServerInbox(deps, signedIn);
    const push = await pushPendingOps(deps);
    if (push.state !== 'idle') return push.state;
    if (!(await getDb().settings.get(CURSOR_KEY))) {
      store.getState().setSnapshotLoaded(0);
      try {
        await loadSnapshot(deps, (n) => store.getState().setSnapshotLoaded(n));
      } finally {
        store.getState().setSnapshotLoaded(null);
      }
    }
    const session = await getSession();
    if (session) await resolveServerInbox(deps, session);
    await pullChanges(deps);
    return 'idle';
  } catch (e) {
    if (e instanceof NetworkError) return 'offline';
    if (e instanceof ApiError && e.status === 401) return 'unauthenticated';
    return 'error';
  }
}

/** Tells this tab's views and the other tabs that IndexedDB has new server data. */
export function notifyDataChanged(): void {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('taskly-data-changed'));
  leadership?.post({ type: 'data-changed' });
}

// ── Background loop (runs only in the leader tab, WEB-S5) ──

const SYNC_INTERVAL_MS = 15000;

let running: Promise<SyncState> | null = null;
let onState: ((s: SyncState) => void) | null = null;
let leadership: SyncLeadership | null = null;
let kickLeader: (() => void) | null = null;

/** Runs one sync cycle; concurrent calls share the same cycle. */
export function triggerSync(deps: ApiDeps = {}): Promise<SyncState> {
  if (!running) {
    running = syncNow(deps).finally(() => {
      running = null;
    });
    running.then(publishState);
  }
  return running;
}

function publishState(s: SyncState): void {
  onState?.(s);
  leadership?.post({ type: 'state', state: s });
}

/**
 * Asks for a sync now: the leader tab runs it, any other tab forwards the request to the
 * leader. Use this after local edits and for the "Sync" button.
 */
export function requestSync(): void {
  if (leadership && !leadership.isLeader) leadership.post({ type: 'local-change' });
  else if (kickLeader) kickLeader();
  else void triggerSync();
}

/** The leader's loop: sync, then wait 15 s when healthy or back off after failures. */
function runLeaderLoop(): () => void {
  let stopped = false;
  let attempt = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;

  const schedule = (ms: number) => {
    if (stopped) return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void tick(), ms);
  };
  async function tick() {
    timer = null;
    const state = await triggerSync();
    if (stopped) return;
    if (state === 'offline' || state === 'error') schedule(backoffDelay(attempt++));
    else {
      attempt = 0;
      schedule(SYNC_INTERVAL_MS);
    }
  }
  const kick = () => {
    attempt = 0;
    schedule(0);
  };
  kickLeader = kick;

  const socket = connectRealtime({
    url: realtimeUrl(defaultBaseUrl(), typeof location !== 'undefined' ? location : { protocol: 'http:', host: 'localhost' }),
    getToken: async () => (await getSession())?.accessToken ?? null,
    onUpdate: kick,
  });
  if (typeof window !== 'undefined') window.addEventListener('online', kick);
  kick();

  return () => {
    stopped = true;
    if (timer) clearTimeout(timer);
    kickLeader = null;
    socket.close();
    if (typeof window !== 'undefined') window.removeEventListener('online', kick);
  };
}

function onSyncMessage(m: SyncMessage): void {
  if (m.type === 'data-changed' && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('taskly-data-changed'));
  } else if (m.type === 'state') {
    onState?.(m.state as SyncState);
  } else if (m.type === 'local-change' && leadership?.isLeader) {
    kickLeader?.();
  }
}

/** Starts background sync: this tab joins the leader election and follows the leader's state. */
export function startSyncService(listener?: (s: SyncState) => void, deps?: ConstructorParameters<typeof SyncLeadership>[0]): void {
  if (leadership) return;
  onState = listener ?? null;
  leadership = new SyncLeadership(deps);
  leadership.onMessage(onSyncMessage);
  // Any write to the queue (from this tab) asks for a push right after its transaction commits
  getDb().syncQueue.hook('creating', () => {
    setTimeout(requestSync, 0);
  });
  leadership.start(runLeaderLoop);
  if (typeof window !== 'undefined') {
    (window as unknown as { __tasklySync?: unknown }).__tasklySync = { isLeader: () => leadership?.isLeader ?? false };
  }
}

export function stopSyncService(): void {
  leadership?.close();
  leadership = null;
  onState = null;
}

/** Server change history of a task card (WEB-F3); null when signed out. Throws on network errors. */
export async function fetchTaskHistory(taskId: string, deps: ApiDeps = {}): Promise<import('../../types').TaskHistoryEntry[] | null> {
  const session = await getSession();
  if (!session) return null;
  const { result } = await authed<import('../../types').TaskHistoryEntry[]>(deps, session, 'GET', `/v1/tasks/${encodeURIComponent(taskId)}/history`);
  return result;
}
