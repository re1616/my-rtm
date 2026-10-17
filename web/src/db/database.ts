// Taskly Web — Dexie Database Setup
import Dexie, { type Table, type EntityTable } from 'dexie';
import type { Task, TaskList, TaskTag, SyncOperation } from '../types';

export class TasklyDB extends Dexie {
  tasks!: EntityTable<Task, 'id'>;
  lists!: EntityTable<TaskList, 'id'>;
  tags!: EntityTable<TaskTag, 'id'>;
  syncQueue!: EntityTable<SyncOperation, 'opId'>;
  settings!: Table<{ key: string; value: string }, 'key'>;

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

  // Seed system lists if not present
  // Dexie 4 uses IndexableType for where conditions; use '1' as truthy marker
  const existingCount = await db.lists
    .where('isSystem')
    .equals('1')
    .count();

  if (existingCount === 0) {
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
    await db.lists.bulkAdd(systemLists);
  }
}