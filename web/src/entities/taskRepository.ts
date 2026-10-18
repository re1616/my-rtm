// Taskly Web — Task Repository (Dexie CRUD + sync queue)
//
// Local-first: every mutation is written to IndexedDB and, in the SAME Dexie transaction,
// an idempotent sync operation is appended to the queue. The sync engine
// (features/sync/SyncService) sends the queue to the server in order.
import { getDb } from '../db/database';
import type { Task, Priority, RepeatMode, SyncOperation, SyncOpType, TaskList } from '../types';

/** RFC 4122 v4 UUID — the server requires real UUIDs for entity ids. */
export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** Local system lists ("sys-*") are views, not server lists — they are never sent. */
function isSystemList(listId: string | undefined): boolean {
  return !listId || listId.startsWith('sys-');
}

/**
 * Server timestamps must be full ISO-8601 instants. A date-only value ("2026-10-19") means
 * local midnight of that day — the same convention as the shared Smart Add parser.
 */
export function toWireTimestamp(value: string | undefined | null): string | null {
  if (!value) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).toISOString();
  return value;
}

/** Client task fields → server wire format (camelCase; the server normalizes names). */
export function toWireFields(task: Partial<Task>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const has = (k: keyof Task) => Object.prototype.hasOwnProperty.call(task, k);
  if (has('name')) out.name = task.name;
  if (has('listId') && !isSystemList(task.listId)) out.listId = task.listId;
  if (has('priority')) out.priority = task.priority ?? null;
  if (has('dueDate')) out.dueAt = toWireTimestamp(task.dueDate);
  if (has('dueHasTime')) out.dueHasTime = task.dueHasTime ?? false;
  if (has('startDate')) out.startAt = toWireTimestamp(task.startDate);
  if (has('estimateMin')) out.estimateMin = task.estimateMin ?? null;
  if (has('repeatRule')) out.rrule = task.repeatRule ?? null;
  if (has('repeatMode')) out.repeatMode = task.repeatMode ?? null;
  if (has('url')) out.url = task.url ?? null;
  return out;
}

let seqCounter = 0;
function nextSeq(): number {
  // Monotonic within the tab; ms resolution × 1000 keeps order across reloads.
  seqCounter = (seqCounter + 1) % 1000;
  return Date.now() * 1000 + seqCounter;
}

function buildOp(
  entity: SyncOperation['entity'],
  entityId: string,
  type: SyncOpType,
  fields: Record<string, unknown> = {},
): SyncOperation {
  return {
    opId: uuid(),
    seq: nextSeq(),
    scope: '',
    entity,
    entityId,
    type,
    fields,
    clientTs: new Date().toISOString(),
    status: 'pending',
    retryCount: 0,
  };
}

async function enqueue(op: SyncOperation): Promise<void> {
  await getDb().syncQueue.add(op);
}

export async function createTask(input: {
  name: string;
  listId?: string;
  priority?: Priority;
  dueDate?: string;
  dueHasTime?: boolean;
  tags?: string[];
  repeatRule?: string;
  repeatMode?: RepeatMode;
  estimateMin?: number;
  note?: string;
}): Promise<Task> {
  const db = getDb();
  const now = new Date().toISOString();
  const task: Task = {
    id: uuid(),
    listId: input.listId || 'sys-inbox',
    name: input.name,
    priority: input.priority || null,
    dueDate: input.dueDate,
    dueHasTime: input.dueHasTime || false,
    estimateMin: input.estimateMin,
    repeatRule: input.repeatRule,
    repeatMode: input.repeatRule ? input.repeatMode || 'every' : input.repeatMode,
    postponedCount: 0,
    tags: input.tags || [],
    notes: input.note,
    version: 1,
    createdAt: now,
    updatedAt: now,
  };

  // Remove undefined fields
  for (const k of Object.keys(task)) {
    const tv = task as unknown as Record<string, unknown>;
    if (tv[k] === undefined) {
      delete tv[k];
    }
  }

  await db.transaction('rw', db.tasks, db.syncQueue, async () => {
    await db.tasks.add(task);
    await enqueue(buildOp('task', task.id, 'create', toWireFields(task)));
    for (const tag of task.tags) {
      await enqueue(buildOp('task', task.id, 'tag_add', { tag }));
    }
  });
  return task;
}

export async function updateTask(id: string, changes: Partial<Task>): Promise<Task | null> {
  const db = getDb();
  return db.transaction('rw', db.tasks, db.syncQueue, async () => {
    const existing = await db.tasks.get(id);
    if (!existing) return null;
    const wire = toWireFields(changes);
    await db.tasks.update(id, {
      ...changes,
      updatedAt: new Date().toISOString(),
      version: existing.version + 1,
    });
    if (Object.keys(wire).length > 0) {
      await enqueue(buildOp('task', id, 'update', wire));
    }
    return (await db.tasks.get(id)) || null;
  });
}

export async function completeTask(id: string): Promise<Task | null> {
  const db = getDb();
  return db.transaction('rw', db.tasks, db.syncQueue, async () => {
    const existing = await db.tasks.get(id);
    if (!existing) return null;
    if (existing.completedAt) return existing; // idempotent
    const now = new Date().toISOString();
    await db.tasks.update(id, { completedAt: now, updatedAt: now, version: existing.version + 1 });
    // The server creates the next instance of a repeating task; it arrives with the next pull.
    await enqueue(buildOp('task', id, 'complete', { completedAt: now }));
    return (await db.tasks.get(id)) || null;
  });
}

export async function uncompleteTask(id: string): Promise<Task | null> {
  const db = getDb();
  return db.transaction('rw', db.tasks, db.syncQueue, async () => {
    const existing = await db.tasks.get(id);
    if (!existing) return null;
    if (!existing.completedAt) return existing;
    await db.tasks.update(id, {
      completedAt: undefined,
      updatedAt: new Date().toISOString(),
      version: existing.version + 1,
    });
    await enqueue(buildOp('task', id, 'uncomplete'));
    return (await db.tasks.get(id)) || null;
  });
}

export async function softDeleteTask(id: string): Promise<void> {
  const db = getDb();
  await db.transaction('rw', db.tasks, db.syncQueue, async () => {
    const existing = await db.tasks.get(id);
    if (!existing || existing.deletedAt) return;
    const now = new Date().toISOString();
    await db.tasks.update(id, { deletedAt: now, updatedAt: now });
    await enqueue(buildOp('task', id, 'delete'));
  });
}

export async function getTasksByList(
  listId: string,
  options?: { showCompleted?: boolean; sortBy?: string; sortAsc?: boolean },
): Promise<Task[]> {
  const db = getDb();

  // Fetch all non-deleted tasks and filter in-memory (simpler than Dexie compound queries)
  const allTasks = await db.tasks.toArray();
  let filtered = allTasks.filter((t) => !t.deletedAt);

  // System views compare in local time
  const now = new Date();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const tomorrowStart = new Date(todayStart.getTime() + 86400000);
  const weekEnd = new Date(todayStart.getTime() + 7 * 86400000);
  const due = (t: Task) => (t.dueDate ? new Date(t.dueDate).getTime() : NaN);

  switch (listId) {
    case 'sys-today':
      filtered = filtered.filter(
        (t) => !t.completedAt && due(t) >= todayStart.getTime() && due(t) < tomorrowStart.getTime(),
      );
      break;
    case 'sys-overdue':
      filtered = filtered.filter((t) => !t.completedAt && due(t) < todayStart.getTime());
      break;
    case 'sys-week':
      filtered = filtered.filter(
        (t) => !t.completedAt && due(t) >= todayStart.getTime() && due(t) < weekEnd.getTime(),
      );
      break;
    case 'sys-completed':
      filtered = filtered.filter((t) => !!t.completedAt);
      break;
    case 'sys-all':
    case 'sys-inbox':
      // All non-deleted tasks (already filtered)
      break;
    default:
      // User list
      filtered = filtered.filter((t) => t.listId === listId);
      break;
  }

  // Apply showCompleted filter (hide completed unless explicitly requested)
  if (!options?.showCompleted && listId !== 'sys-completed') {
    filtered = filtered.filter((t) => !t.completedAt);
  }

  // Sorting
  const sortField = options?.sortBy || 'createdAt';
  const asc = options?.sortAsc !== false;
  const colAny = filtered as unknown as Array<Record<string, unknown>>;
  colAny.sort((a, b) => {
    const aVal = a[sortField] as string | number | undefined;
    const bVal = b[sortField] as string | number | undefined;
    if (!aVal && !bVal) return 0;
    if (!aVal) return asc ? -1 : 1;
    if (!bVal) return asc ? 1 : -1;
    const cmp =
      typeof aVal === 'string'
        ? (aVal as string).localeCompare(bVal as string)
        : (aVal as number) - (bVal as number);
    return asc ? cmp : -cmp;
  });

  return filtered;
}

export async function getTaskById(id: string): Promise<Task | null> {
  const db = getDb();
  return (await db.tasks.get(id)) || null;
}

/** Low-level enqueue (used by tests and for entities without a repository yet). */
export async function addToSyncQueue(
  op: Omit<SyncOperation, 'opId' | 'status' | 'retryCount' | 'seq' | 'scope'> &
    Partial<Pick<SyncOperation, 'scope'>>,
): Promise<SyncOperation> {
  const syncOp: SyncOperation = {
    ...buildOp(op.entity, op.entityId, op.type, op.fields),
    clientTs: op.clientTs,
    baseVersion: op.baseVersion,
    scope: op.scope ?? '',
  };
  await enqueue(syncOp);
  return syncOp;
}

/** Ops waiting to be sent, strictly in queue order. Rejected ops are retried up to [maxRetries]. */
export async function getPendingSyncOps(maxRetries = 5): Promise<SyncOperation[]> {
  const db = getDb();
  const all = await db.syncQueue.orderBy('seq').toArray();
  return all.filter(
    (op) => op.status === 'pending' || (op.status === 'failed' && op.retryCount < maxRetries),
  );
}

export async function markSyncOpStatus(
  opId: string,
  status: SyncOperation['status'],
  error?: string,
): Promise<void> {
  const db = getDb();
  if (status === 'failed') {
    const op = await db.syncQueue.get(opId);
    await db.syncQueue.update(opId, {
      status,
      retryCount: (op?.retryCount ?? 0) + 1,
      lastError: error,
    });
  } else {
    await db.syncQueue.update(opId, { status });
  }
}

/** Server acknowledged the op: it is durable there, drop it from the queue. */
export async function removeSyncOps(opIds: string[]): Promise<void> {
  await getDb().syncQueue.bulkDelete(opIds);
}

export async function getLists(): Promise<TaskList[]> {
  const db = getDb();
  const all = await db.lists.toArray();
  return all.filter((l) => !l.archived).sort((a, b) => a.sortOrder - b.sortOrder);
}

export async function findListByName(name: string): Promise<TaskList | null> {
  const needle = name.trim().toLowerCase();
  const all = await getDb().lists.toArray();
  return all.find((l) => !l.isSystem && !l.archived && l.name.toLowerCase() === needle) || null;
}

export async function createList(name: string): Promise<TaskList> {
  const db = getDb();
  const now = new Date().toISOString();
  return db.transaction('rw', db.lists, db.syncQueue, async () => {
    const all = await db.lists.toArray();
    const maxOrder = all.reduce((max, l) => Math.max(max, l.sortOrder), 0);
    const list: TaskList = {
      id: uuid(),
      name,
      sortOrder: maxOrder + 1,
      archived: false,
      createdAt: now,
      updatedAt: now,
    };
    await db.lists.add(list);
    await enqueue(buildOp('list', list.id, 'create', { name, sortOrder: list.sortOrder }));
    return list;
  });
}

// ── Smart Lists ──

export async function createSmartList(name: string, filterQuery: string): Promise<TaskList> {
  const db = getDb();
  const now = new Date().toISOString();
  return db.transaction('rw', db.lists, db.syncQueue, async () => {
    const all = await db.lists.toArray();
    const maxOrder = all.reduce((max, l) => Math.max(max, l.sortOrder), 0);
    const list: TaskList = {
      id: uuid(), name, isSmart: true, filterQuery,
      sortOrder: maxOrder + 1, archived: false, createdAt: now, updatedAt: now,
    };
    await db.lists.add(list);
    await enqueue(buildOp('list', list.id, 'create', { name, isSmart: true, filterQuery, sortOrder: list.sortOrder }));
    return list;
  });
}

export async function getSmartLists(): Promise<TaskList[]> {
  const db = getDb();
  const all = await db.lists.toArray();
  return all.filter((l) => l.isSmart && !l.archived).sort((a, b) => a.sortOrder - b.sortOrder);
}

export async function deleteSmartList(id: string): Promise<void> {
  await getDb().lists.update(id, { archived: true, updatedAt: new Date().toISOString() });
}

export async function updateSmartList(id: string, changes: Partial<TaskList>): Promise<void> {
  changes.updatedAt = new Date().toISOString();
  await getDb().lists.update(id, changes);
}

export async function getListById(id: string): Promise<TaskList | null> {
  return (await getDb().lists.get(id)) || null;
}

// ── Subtasks ──

export async function addSubtask(parentId: string, name: string): Promise<Task> {
  const db = getDb();
  const now = new Date().toISOString();
  const task: Task = {
    id: uuid(), listId: 'sys-subtasks-' + parentId, name,
    priority: null, dueHasTime: false, postponedCount: 0, tags: [], parentId,
    version: 1, createdAt: now, updatedAt: now,
  };
  await db.transaction('rw', db.tasks, db.syncQueue, async () => {
    await db.tasks.add(task);
    await enqueue(buildOp('task', task.id, 'create', { name, parentId }));
  });
  return task;
}

export async function getSubtasks(parentId: string): Promise<Task[]> {
  const all = await getDb().tasks.toArray();
  return all.filter((t) => t.parentId === parentId && !t.deletedAt);
}

// ── Query-based filtering ──

export async function getTasksByQuery(query: string, options?: { showCompleted?: boolean }): Promise<Task[]> {
  const db = getDb();
  const all = await db.tasks.toArray();
  let filtered = all.filter((t) => !t.deletedAt);
  if (!query || !query.trim()) return filtered;
  const parts = query.split('&').map((p) => p.trim()).filter(Boolean);
  for (const part of parts) {
    const colonIdx = part.indexOf(':');
    if (colonIdx < 0) { const q = part.toLowerCase(); filtered = filtered.filter((t) => t.name.toLowerCase().includes(q)); continue; }
    const field = part.slice(0, colonIdx).trim().toLowerCase();
    const value = part.slice(colonIdx + 1).trim().toLowerCase();
    switch (field) {
      case 'priority': { const p = parseInt(value, 10); filtered = filtered.filter((t) => t.priority === (isNaN(p) ? null : p)); break; }
      case 'due': if (value==='today') { const s=new Date(new Date().getFullYear(),new Date().getMonth(),new Date().getDate()).getTime(); filtered=filtered.filter(t=>t.dueDate&&new Date(t.dueDate).getTime()>=s&&new Date(t.dueDate).getTime()<s+86400000); } break;
      case 'tag': case 'tags': filtered=filtered.filter(t=>t.tags.some(tag=>tag.toLowerCase().includes(value))); break;
      case 'list': filtered=filtered.filter(t=>t.listId.toLowerCase().includes(value)); break;
      case 'completed': if(value==='yes'||value==='true') filtered=filtered.filter(t=>!!t.completedAt); else filtered=filtered.filter(t=>!t.completedAt); break;
      case 'isrepeating': if(value==='yes'||value==='true') filtered=filtered.filter(t=>!!t.repeatRule); else filtered=filtered.filter(t=>!t.repeatRule); break;
    }
  }
  if (!options?.showCompleted) filtered = filtered.filter((t) => !t.completedAt);
  return filtered;
}
