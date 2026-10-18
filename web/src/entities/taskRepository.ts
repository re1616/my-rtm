// Taskly Web — Task Repository (Dexie CRUD + sync queue)
//
// Local-first: every mutation is written to IndexedDB and, in the SAME Dexie transaction,
// an idempotent sync operation is appended to the queue. The sync engine
// (features/sync/SyncService) sends the queue to the server in order.
import { getDb } from '../db/database';
import type {
  Task,
  Priority,
  RepeatMode,
  SyncOperation,
  SyncOpType,
  TaskAttachment,
  TaskList,
  TaskLocation,
  TaskNote,
} from '../types';

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

export const INBOX_ID = 'sys-inbox';

/**
 * Local system views ("sys-today", …) are not lists and are never sent. The inbox is the one
 * exception: "sys-inbox" travels in the queue as a marker and the sync engine replaces it with
 * the server's inbox id when sending, so moving a task back to Inbox is synced too.
 */
function wireListId(listId: string | undefined): string | undefined {
  if (!listId) return undefined;
  if (listId === INBOX_ID) return INBOX_ID;
  return listId.startsWith('sys-') ? undefined : listId;
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
  if (has('listId')) {
    const id = wireListId(task.listId);
    if (id) out.listId = id;
  }
  if (has('parentId')) out.parentId = task.parentId ?? null;
  if (has('locationId')) out.locationId = task.locationId ?? null;
  if (has('priority')) out.priority = task.priority ?? null;
  if (has('dueDate')) out.dueAt = toWireTimestamp(task.dueDate);
  if (has('dueHasTime')) out.dueHasTime = task.dueHasTime ?? false;
  if (has('startDate')) out.startAt = toWireTimestamp(task.startDate);
  if (has('estimateMin')) out.estimateMin = task.estimateMin ?? null;
  if (has('repeatRule')) out.rrule = task.repeatRule ?? null;
  if (has('repeatMode')) out.repeatMode = task.repeatMode ?? null;
  if (has('url')) out.url = task.url ?? null;
  if (has('postponedCount')) out.postponedCount = task.postponedCount ?? 0;
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

function dropUndefined<T extends object>(o: T): T {
  for (const k of Object.keys(o)) {
    if ((o as Record<string, unknown>)[k] === undefined) delete (o as Record<string, unknown>)[k];
  }
  return o;
}

// ── Tasks ────────────────────────────────────────────────────────────────

export interface NewTaskInput {
  name: string;
  listId?: string;
  parentId?: string;
  locationId?: string;
  priority?: Priority;
  dueDate?: string;
  dueHasTime?: boolean;
  startDate?: string;
  tags?: string[];
  repeatRule?: string;
  repeatMode?: RepeatMode;
  estimateMin?: number;
  url?: string;
  note?: string;
}

export async function createTask(input: NewTaskInput): Promise<Task> {
  const db = getDb();
  const now = new Date().toISOString();
  const task: Task = dropUndefined({
    id: uuid(),
    listId: input.listId || INBOX_ID,
    parentId: input.parentId,
    locationId: input.locationId,
    name: input.name,
    priority: input.priority || null,
    dueDate: input.dueDate,
    dueHasTime: input.dueHasTime || false,
    startDate: input.startDate,
    estimateMin: input.estimateMin,
    repeatRule: input.repeatRule,
    repeatMode: input.repeatRule ? input.repeatMode || 'every' : input.repeatMode,
    url: input.url,
    postponedCount: 0,
    tags: [...new Set(input.tags || [])],
    version: 1,
    createdAt: now,
    updatedAt: now,
  });

  await db.transaction('rw', [db.tasks, db.notes, db.syncQueue], async () => {
    await db.tasks.add(task);
    await enqueue(buildOp('task', task.id, 'create', toWireFields(task)));
    for (const tag of task.tags) {
      await enqueue(buildOp('task', task.id, 'tag_add', { tag }));
    }
    if (input.note?.trim()) await insertNote(task.id, input.note.trim());
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
      await enqueue(buildOp('task', id, changes.listId !== undefined && Object.keys(wire).length === 1 ? 'move' : 'update', wire));
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

/** Completed ↔ open in one action (checkbox, "C" on a completed task). */
export async function toggleComplete(id: string): Promise<Task | null> {
  const t = await getDb().tasks.get(id);
  if (!t) return null;
  return t.completedAt ? uncompleteTask(id) : completeTask(id);
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

/** Undo of a deletion (FR-6): the server clears deleted_at as well. */
export async function restoreTask(id: string): Promise<void> {
  const db = getDb();
  await db.transaction('rw', db.tasks, db.syncQueue, async () => {
    const existing = await db.tasks.get(id);
    if (!existing || !existing.deletedAt) return;
    await db.tasks.update(id, { deletedAt: undefined, updatedAt: new Date().toISOString() });
    await enqueue(buildOp('task', id, 'restore'));
  });
}

function addDays(iso: string, days: number): string {
  const d = new Date(iso);
  d.setDate(d.getDate() + days);
  return d.toISOString();
}

function localMidnight(daysFromToday: number): string {
  const n = new Date();
  return new Date(n.getFullYear(), n.getMonth(), n.getDate() + daysFromToday).toISOString();
}

/**
 * Postpone (SPEC FR-3): due date + 1 day, keeping the time of day; a task without a due date
 * becomes due tomorrow. The counter is visible in the card and usable in queries (postponed:>N).
 */
export async function postponeTask(id: string): Promise<Task | null> {
  const t = await getDb().tasks.get(id);
  if (!t) return null;
  const dueDate = t.dueDate ? addDays(t.dueDate, 1) : localMidnight(1);
  return updateTask(id, { dueDate, dueHasTime: t.dueDate ? t.dueHasTime : false, postponedCount: (t.postponedCount ?? 0) + 1 });
}

export async function addTagsToTask(id: string, tags: string[]): Promise<void> {
  const db = getDb();
  await db.transaction('rw', db.tasks, db.syncQueue, async () => {
    const t = await db.tasks.get(id);
    if (!t) return;
    const current = new Set(t.tags);
    const added = tags.map((x) => x.trim().replace(/^#/, '')).filter((x) => x && !current.has(x));
    if (added.length === 0) return;
    await db.tasks.update(id, { tags: [...t.tags, ...added], updatedAt: new Date().toISOString() });
    for (const tag of added) await enqueue(buildOp('task', id, 'tag_add', { tag }));
  });
}

export async function removeTagsFromTask(id: string, tags: string[]): Promise<void> {
  const db = getDb();
  await db.transaction('rw', db.tasks, db.syncQueue, async () => {
    const t = await db.tasks.get(id);
    if (!t) return;
    const removed = tags.filter((x) => t.tags.includes(x));
    if (removed.length === 0) return;
    await db.tasks.update(id, { tags: t.tags.filter((x) => !removed.includes(x)), updatedAt: new Date().toISOString() });
    for (const tag of removed) await enqueue(buildOp('task', id, 'tag_remove', { tag }));
  });
}

/** Live (non-deleted) tasks, all lists. */
export async function getAllTasks(): Promise<Task[]> {
  return (await getDb().tasks.toArray()).filter((t) => !t.deletedAt);
}

export async function getSubtasks(parentId: string): Promise<Task[]> {
  const rows = await getDb().tasks.where('parentId').equals(parentId).toArray();
  return rows.filter((t) => !t.deletedAt).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

export interface ListQueryOptions {
  showCompleted?: boolean;
  sortBy?: string;
  sortAsc?: boolean;
}

/** Day boundaries in local time for the system views. */
function dayStart(offset: number): number {
  const n = new Date();
  return new Date(n.getFullYear(), n.getMonth(), n.getDate() + offset).getTime();
}

/** Does the task belong to a system view or user list (before the completed filter)? */
export function inView(t: Task, listId: string): boolean {
  const due = t.dueDate ? new Date(t.dueDate).getTime() : NaN;
  switch (listId) {
    case 'sys-today':
      return !t.completedAt && due >= dayStart(0) && due < dayStart(1);
    case 'sys-tomorrow':
      return !t.completedAt && due >= dayStart(1) && due < dayStart(2);
    case 'sys-overdue':
      return !t.completedAt && due < dayStart(0);
    case 'sys-week':
      return !t.completedAt && due >= dayStart(0) && due < dayStart(7);
    case 'sys-completed':
      return !!t.completedAt;
    case 'sys-all':
      return true;
    case INBOX_ID:
      return !t.listId || t.listId === INBOX_ID;
    default:
      return t.listId === listId;
  }
}

/** Sort: missing values last; priority 1 before 2 before 3. */
export function sortTasks(tasks: Task[], sortBy = 'createdAt', asc = true): Task[] {
  const key = (t: Task): string | number | undefined => {
    switch (sortBy) {
      case 'priority':
        return t.priority ?? undefined;
      case 'dueDate':
        return t.dueDate ? new Date(t.dueDate).getTime() : undefined;
      case 'name':
        return t.name.toLocaleLowerCase();
      default:
        return (t as unknown as Record<string, string | undefined>)[sortBy];
    }
  };
  return [...tasks].sort((a, b) => {
    const av = key(a);
    const bv = key(b);
    if (av === undefined && bv === undefined) return 0;
    if (av === undefined) return 1;
    if (bv === undefined) return -1;
    const cmp = typeof av === 'number' && typeof bv === 'number' ? av - bv : String(av).localeCompare(String(bv));
    return asc ? cmp : -cmp;
  });
}

/**
 * Tasks of a system view or user list. Subtasks are shown inside their parent's card, so only
 * top-level tasks (or subtasks whose parent is not in the view) are listed.
 */
export async function getTasksByList(listId: string, options?: ListQueryOptions): Promise<Task[]> {
  const all = await getAllTasks();
  let filtered = all.filter((t) => inView(t, listId));
  if (!options?.showCompleted && listId !== 'sys-completed') {
    filtered = filtered.filter((t) => !t.completedAt);
  }
  const ids = new Set(filtered.map((t) => t.id));
  filtered = filtered.filter((t) => !t.parentId || !ids.has(t.parentId));
  return sortTasks(filtered, options?.sortBy || 'createdAt', options?.sortAsc !== false);
}

export async function getTaskById(id: string): Promise<Task | null> {
  const db = getDb();
  return (await db.tasks.get(id)) || null;
}

// ── Notes (markdown) and attachments ─────────────────────────────────────

async function insertNote(taskId: string, content: string): Promise<TaskNote> {
  const now = new Date().toISOString();
  const note: TaskNote = { id: uuid(), taskId, content, createdAt: now, updatedAt: now };
  await getDb().notes.add(note);
  await enqueue(buildOp('note', note.id, 'create', { taskId, content }));
  return note;
}

export async function addNote(taskId: string, content: string): Promise<TaskNote> {
  const db = getDb();
  return db.transaction('rw', [db.notes, db.syncQueue], () => insertNote(taskId, content));
}

export async function updateNote(id: string, content: string): Promise<void> {
  const db = getDb();
  await db.transaction('rw', [db.notes, db.syncQueue], async () => {
    const n = await db.notes.get(id);
    if (!n || n.content === content) return;
    await db.notes.update(id, { content, updatedAt: new Date().toISOString() });
    await enqueue(buildOp('note', id, 'update', { content }));
  });
}

export async function deleteNote(id: string): Promise<void> {
  const db = getDb();
  await db.transaction('rw', [db.notes, db.syncQueue], async () => {
    if (!(await db.notes.get(id))) return;
    await db.notes.delete(id);
    await enqueue(buildOp('note', id, 'delete'));
  });
}

export async function getNotes(taskId: string): Promise<TaskNote[]> {
  const rows = await getDb().notes.where('taskId').equals(taskId).toArray();
  return rows.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

/** Only http(s) links: anything else (javascript:, data:, file:) is refused here and on the server. */
export function isSafeLink(url: string): boolean {
  try {
    const u = new URL(url);
    return (u.protocol === 'https:' || u.protocol === 'http:') && !!u.hostname;
  } catch {
    return false;
  }
}

export async function addAttachment(taskId: string, url: string, name?: string): Promise<TaskAttachment> {
  const trimmed = url.trim();
  if (!isSafeLink(trimmed)) throw new Error('Только ссылки http(s)');
  const db = getDb();
  const att: TaskAttachment = { id: uuid(), taskId, url: trimmed, name: name?.trim() || trimmed, createdAt: new Date().toISOString() };
  await db.transaction('rw', [db.attachments, db.syncQueue], async () => {
    await db.attachments.add(att);
    await enqueue(buildOp('attachment', att.id, 'create', { taskId, url: att.url, name: att.name }));
  });
  return att;
}

export async function removeAttachment(id: string): Promise<void> {
  const db = getDb();
  await db.transaction('rw', [db.attachments, db.syncQueue], async () => {
    if (!(await db.attachments.get(id))) return;
    await db.attachments.delete(id);
    await enqueue(buildOp('attachment', id, 'delete'));
  });
}

export async function getAttachments(taskId: string): Promise<TaskAttachment[]> {
  return getDb().attachments.where('taskId').equals(taskId).toArray();
}

// ── Sync queue ───────────────────────────────────────────────────────────

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

// ── Lists, tags, locations ───────────────────────────────────────────────

export async function getLists(): Promise<TaskList[]> {
  const db = getDb();
  const all = await db.lists.toArray();
  return all.filter((l) => !l.archived).sort((a, b) => a.sortOrder - b.sortOrder);
}

export async function findListByName(name: string): Promise<TaskList | null> {
  const needle = name.trim().toLowerCase();
  const all = await getDb().lists.toArray();
  return all.find((l) => !l.isSystem && !l.isSmart && !l.archived && l.name.toLowerCase() === needle) || null;
}

export async function createList(name: string, smart?: { query: string }): Promise<TaskList> {
  const db = getDb();
  const now = new Date().toISOString();
  return db.transaction('rw', db.lists, db.syncQueue, async () => {
    const all = await db.lists.toArray();
    const maxOrder = all.reduce((max, l) => Math.max(max, l.sortOrder), 0);
    const list: TaskList = dropUndefined({
      id: uuid(),
      name,
      isSmart: smart ? true : undefined,
      filterQuery: smart?.query,
      sortOrder: maxOrder + 1,
      archived: false,
      createdAt: now,
      updatedAt: now,
    });
    await db.lists.add(list);
    const fields: Record<string, unknown> = { name, sortOrder: list.sortOrder };
    if (smart) Object.assign(fields, { isSmart: true, query: smart.query });
    await enqueue(buildOp('list', list.id, 'create', fields));
    return list;
  });
}

/** Saved search (SPEC 4.4 scenario 3): a smart list with the shared query language. */
export function createSmartList(name: string, query: string): Promise<TaskList> {
  return createList(name, { query });
}

export async function updateList(id: string, changes: Partial<Pick<TaskList, 'name' | 'filterQuery' | 'archived' | 'color'>>): Promise<void> {
  const db = getDb();
  await db.transaction('rw', db.lists, db.syncQueue, async () => {
    const l = await db.lists.get(id);
    if (!l || l.isSystem) return;
    await db.lists.update(id, { ...changes, updatedAt: new Date().toISOString() });
    const fields: Record<string, unknown> = {};
    if (changes.name !== undefined) fields.name = changes.name;
    if (changes.filterQuery !== undefined) fields.query = changes.filterQuery;
    if (changes.archived !== undefined) fields.archived = changes.archived;
    if (changes.color !== undefined) fields.color = changes.color;
    await enqueue(buildOp('list', id, 'update', fields));
  });
}

/** Deletes a user list; its tasks go back to Inbox (locally and, through the queue, on the server). */
export async function deleteList(id: string): Promise<void> {
  const db = getDb();
  await db.transaction('rw', db.lists, db.tasks, db.syncQueue, async () => {
    const l = await db.lists.get(id);
    if (!l || l.isSystem) return;
    const tasks = await db.tasks.where('listId').equals(id).toArray();
    for (const t of tasks) {
      await db.tasks.update(t.id, { listId: INBOX_ID, updatedAt: new Date().toISOString() });
      await enqueue(buildOp('task', t.id, 'move', { listId: INBOX_ID }));
    }
    await db.lists.delete(id);
    await enqueue(buildOp('list', id, 'delete'));
  });
}

export async function getLocations(): Promise<TaskLocation[]> {
  return (await getDb().locations.toArray()).sort((a, b) => a.name.localeCompare(b.name));
}

/** All tag names in use (tasks) or known (tags table), alphabetically. */
export async function getTagNames(): Promise<string[]> {
  const db = getDb();
  const names = new Set<string>();
  for (const t of await db.tags.toArray()) names.add(t.name);
  for (const t of await getAllTasks()) for (const tag of t.tags) names.add(tag);
  return [...names].sort((a, b) => a.localeCompare(b));
}

/** New location (Smart Add "@name"); synced as its own entity (SYNC-6). */
export async function createLocation(name: string): Promise<TaskLocation> {
  const db = getDb();
  const now = new Date().toISOString();
  const loc: TaskLocation = { id: uuid(), name: name.trim(), createdAt: now, updatedAt: now };
  await db.transaction('rw', db.locations, db.syncQueue, async () => {
    await db.locations.add(loc);
    await enqueue(buildOp('location', loc.id, 'create', { name: loc.name }));
  });
  return loc;
}
