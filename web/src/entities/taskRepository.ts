// Taskly Web — Task Repository (Dexie CRUD)
import { getDb } from '../db/database';
import type { Task, Priority, RepeatMode, SyncOperation, TaskList } from '../types';

// Simple UUIDv4 generator (UUIDv7 requires a timestamp component; v4 is fine for stub)
function uuid(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
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
    repeatMode: input.repeatMode,
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

  await db.tasks.add(task);
  return task;
}

export async function updateTask(
  id: string,
  changes: Partial<Task>,
): Promise<Task | null> {
  const db = getDb();
  const existing = await db.tasks.get(id);
  if (!existing) return null;

  changes.updatedAt = new Date().toISOString();
  changes.version = existing.version + 1;

  await db.tasks.update(id, changes);
  const updated = await db.tasks.get(id);
  return updated || null;
}

export async function completeTask(id: string): Promise<Task | null> {
  const now = new Date().toISOString();
  const db = getDb();
  const existing = await db.tasks.get(id);
  if (!existing) return null;
  await db.tasks.update(id, {
    completedAt: now,
    updatedAt: now,
    version: existing.version + 1,
  } as Partial<Task>);
  return (await db.tasks.get(id)) || null;
}

export async function uncompleteTask(id: string): Promise<Task | null> {
  const db = getDb();
  const existing = await db.tasks.get(id);
  if (!existing) return null;

  // Dexie: set to null to clear the field; we pass as any since Partial<Task> requires string
  await db.tasks.update(id, {
    completedAt: null,
    updatedAt: new Date().toISOString(),
    version: existing.version + 1,
  } as unknown as Partial<Task>);
  return (await db.tasks.get(id)) || null;
}

export async function softDeleteTask(id: string): Promise<void> {
  const db = getDb();
  const now = new Date().toISOString();
  const existing = await db.tasks.get(id);
  if (!existing) return;
  await db.tasks.update(id, {
    deletedAt: now,
    updatedAt: now,
  } as Partial<Task>);
}

export async function getTasksByList(
  listId: string,
  options?: { showCompleted?: boolean; sortBy?: string; sortAsc?: boolean },
): Promise<Task[]> {
  const db = getDb();

  // Fetch all non-deleted tasks and filter in-memory (simpler than Dexie compound queries)
  const allTasks = await db.tasks.toArray();
  let filtered = allTasks.filter((t) => !t.deletedAt);

  // System view filters
  const now = new Date();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const todayStr = todayStart.toISOString().split('T')[0];
  const tomorrowStart = new Date(todayStart.getTime() + 86400000);
  const tomorrowStr = tomorrowStart.toISOString().split('T')[0];
  const weekEnd = new Date(todayStart.getTime() + 7 * 86400000);
  const weekEndStr = weekEnd.toISOString();

  switch (listId) {
    case 'sys-today':
      filtered = filtered.filter(
        (t) => !t.completedAt && t.dueDate && t.dueDate >= todayStr && t.dueDate < tomorrowStr,
      );
      break;
    case 'sys-overdue':
      filtered = filtered.filter(
        (t) => !t.completedAt && t.dueDate && t.dueDate < todayStr,
      );
      break;
    case 'sys-week':
      filtered = filtered.filter(
        (t) => !t.completedAt && t.dueDate && t.dueDate >= todayStr && t.dueDate < weekEndStr,
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

export async function addToSyncQueue(
  op: Omit<SyncOperation, 'opId' | 'status' | 'retryCount'>,
): Promise<SyncOperation> {
  const db = getDb();
  const syncOp: SyncOperation = {
    ...op,
    opId: uuid(),
    status: 'pending',
    retryCount: 0,
  };
  await db.syncQueue.add(syncOp);
  return syncOp;
}

export async function getPendingSyncOps(): Promise<SyncOperation[]> {
  const db = getDb();
  const all = await db.syncQueue.toArray();
  return all.filter((op) => op.status === 'pending' || op.status === 'failed');
}

export async function markSyncOpStatus(
  opId: string,
  status: SyncOperation['status'],
): Promise<void> {
  const db = getDb();
  await db.syncQueue.update(opId, { status } as Partial<SyncOperation>);
}

export async function getLists(): Promise<TaskList[]> {
  const db = getDb();
  const all = await db.lists.toArray();
  return all.filter((l) => !l.archived).sort((a, b) => a.sortOrder - b.sortOrder);
}

export async function createList(name: string): Promise<TaskList> {
  const db = getDb();
  const now = new Date().toISOString();
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
  return list;
}