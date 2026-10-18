// Taskly Web — Dexie Database Setup
import Dexie, { type Table, type EntityTable } from 'dexie';
import type { Task, TaskList, TaskTag, TaskLocation, TaskNote, TaskAttachment, SyncOperation } from '../types';

export class TasklyDB extends Dexie {
  tasks!: EntityTable<Task, 'id'>;
  lists!: EntityTable<TaskList, 'id'>;
  tags!: EntityTable<TaskTag, 'id'>;
  syncQueue!: EntityTable<SyncOperation, 'opId'>;
  settings!: Table<{ key: string; value: string }, string>;
  locations!: EntityTable<TaskLocation, 'id'>;
  notes!: EntityTable<TaskNote, 'id'>;
  attachments!: EntityTable<TaskAttachment, 'id'>;

  constructor() {
    super('taskly');
    this.version(3).stores({
      tasks:
        'id, listId, priority, dueDate, completedAt, deletedAt, version, createdAt, updatedAt, name',
      lists: 'id, name, isSystem, sortOrder, archived',
      tags: 'id, name',
      syncQueue: 'opId, entityId, status, clientTs',
      settings: 'key',
    });
    // v4: ordered sync queue (seq)
    this.version(4)
      .stores({ syncQueue: 'opId, seq, entityId, status, clientTs' })
      .upgrade(async (tx) => {
        let i = 0;
        await tx
          .table('syncQueue')
          .toCollection()
          .modify((op: Record<string, unknown>) => {
            op.seq = Date.parse(String(op.clientTs ?? '')) * 1000 + i++ || i;
            if (op.status === 'sent' || op.status === 'sending') op.status = 'pending';
          });
      });
    // v5: location entities (SYNC-6)
    this.version(5).stores({
      locations: 'id, name, createdAt',
      // tasks/lists/tags/syncQueue/settings unchanged
    });
    // v6: task card (WEB-F3) — notes and attachments as synced entities, subtasks by parentId.
    // The legacy single `task.notes` string becomes a note row, queued for sync like any edit.
    this.version(6)
      .stores({
        tasks: 'id, listId, parentId, priority, dueDate, completedAt, deletedAt, version, createdAt, updatedAt, name',
        notes: 'id, taskId, updatedAt',
        attachments: 'id, taskId',
      })
      .upgrade(async (tx) => {
        const now = new Date().toISOString();
        let i = 0;
        const tasks = (await tx.table('tasks').toArray()) as Task[];
        for (const t of tasks) {
          if (!t.notes || t.deletedAt) continue;
          const id = crypto.randomUUID();
          await tx.table('notes').add({ id, taskId: t.id, content: t.notes, createdAt: now, updatedAt: now });
          await tx.table('syncQueue').add({
            opId: crypto.randomUUID(),
            seq: Date.now() * 1000 + 900 + (i++ % 100),
            scope: '',
            entity: 'note',
            entityId: id,
            type: 'create',
            fields: { taskId: t.id, content: t.notes },
            clientTs: now,
            status: 'pending',
            retryCount: 0,
          } satisfies SyncOperation);
          await tx.table('tasks').update(t.id, { notes: undefined });
        }
      });
  }
}

let _db: TasklyDB | null = null;

export function getDb(): TasklyDB {
  if (!_db) {
    _db = new TasklyDB();
  }
  return _db;
}

export async function initializeDb(): Promise<void> {
  const db = getDb();

  // Seed system lists if not present. (Booleans are not indexable in IndexedDB, so the old
  // `where('isSystem').equals('1')` check always returned 0 and the second start crashed
  // on a duplicate-key bulkAdd.)
  const existing = await db.lists.get('sys-inbox');

  if (!existing) {
    const now = new Date().toISOString();
    const systemLists: TaskList[] = [
      {
        id: 'sys-inbox',
        name: 'Входящие',
        isSystem: true,
        sortOrder: 0,
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 'sys-today',
        name: 'Сегодня',
        isSystem: true,
        sortOrder: 1,
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 'sys-tomorrow',
        name: 'Завтра',
        isSystem: true,
        sortOrder: 1,
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 'sys-overdue',
        name: 'Просрочено',
        isSystem: true,
        sortOrder: 2,
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 'sys-week',
        name: 'Неделя',
        isSystem: true,
        sortOrder: 3,
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 'sys-all',
        name: 'Все задачи',
        isSystem: true,
        sortOrder: 4,
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 'sys-completed',
        name: 'Выполненные',
        isSystem: true,
        sortOrder: 5,
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
    ];
    await db.lists.bulkPut(systemLists);
  }
}