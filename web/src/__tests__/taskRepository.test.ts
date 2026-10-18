// Taskly Web — Task Repository Tests
import { describe, it, expect, beforeAll } from 'vitest';
import { initializeDb, getDb } from '../db/database';
import {
  createTask,
  completeTask,
  softDeleteTask,
  getTasksByList,
  getTaskById,
  addToSyncQueue,
  getPendingSyncOps,
  markSyncOpStatus,
  createList,
  getLists,
} from '../entities/taskRepository';

// Initialize Dexie with in-memory storage for tests
beforeAll(async () => {
  // Dexie will use its default storage; tests run isolated
  await initializeDb();
});

describe('Task Repository', () => {
  it('creates a task', async () => {
    const task = await createTask({ name: 'Test task' });
    expect(task.id).toBeDefined();
    expect(task.name).toBe('Test task');
    expect(task.listId).toBe('sys-inbox');
    expect(task.version).toBe(1);
  });

  it('creates a task in a specific list', async () => {
    const task = await createTask({ name: 'Work task', listId: 'sys-today' });
    expect(task.listId).toBe('sys-today');
  });

  it('creates a task with priority', async () => {
    const task = await createTask({ name: 'High priority', priority: 1 });
    expect(task.priority).toBe(1);
  });

  it('creates a task with due date', async () => {
    const dueDate = new Date(Date.now() + 86400000).toISOString();
    const task = await createTask({ name: 'With due', dueDate });
    expect(task.dueDate).toBe(dueDate);
  });

  it('retrieves tasks by list', async () => {
    await createTask({ name: 'Inbox task A', listId: 'sys-inbox' });
    await createTask({ name: 'Inbox task B', listId: 'sys-inbox' });
    const tasks = await getTasksByList('sys-inbox');
    // Should include the two we just created plus previous ones
    const inboxTasks = tasks.filter((t) => t.listId === 'sys-inbox');
    expect(inboxTasks.length).toBeGreaterThanOrEqual(2);
  });

  it('completes a task', async () => {
    const task = await createTask({ name: 'To complete' });
    const completed = await completeTask(task.id);
    expect(completed).not.toBeNull();
    expect(completed!.completedAt).toBeDefined();
  });

  it('soft deletes a task', async () => {
    const task = await createTask({ name: 'To delete' });
    await softDeleteTask(task.id);
    const deleted = await getTaskById(task.id);
    expect(deleted).not.toBeNull();
    expect(deleted!.deletedAt).toBeDefined();
  });

  it('filters out deleted tasks from list view', async () => {
    const before = await getTasksByList('sys-all');
    const task = await createTask({ name: 'To hide' });
    await softDeleteTask(task.id);
    const after = await getTasksByList('sys-all');
    // after shouldn't contain our deleted task
    expect(after.find((t) => t.id === task.id)).toBeUndefined();
  });

  it('retrieves task by id', async () => {
    const task = await createTask({ name: 'Find me' });
    const found = await getTaskById(task.id);
    expect(found).not.toBeNull();
    expect(found!.name).toBe('Find me');
  });
});

describe('Sync Queue', () => {
  it('adds an operation to sync queue', async () => {
    const op = await addToSyncQueue({
      scope: 'user:test',
      entity: 'task',
      entityId: 'test-id',
      type: 'create',
      fields: { name: 'Test' },
      baseVersion: 1,
      clientTs: new Date().toISOString(),
    });
    expect(op.opId).toBeDefined();
    expect(op.status).toBe('pending');
  });

  it('retrieves pending sync operations', async () => {
    const pending = await getPendingSyncOps();
    expect(pending.length).toBeGreaterThanOrEqual(1);
    expect(pending[0].status).toBe('pending');
  });

  it('marks sync operation status', async () => {
    const pending = await getPendingSyncOps();
    if (pending.length > 0) {
      await markSyncOpStatus(pending[0].opId, 'sending');
      const checkAgain = await getPendingSyncOps();
      expect(checkAgain.find((op) => op.opId === pending[0].opId)).toBeUndefined();
    }
  });
});

describe('List Repository', () => {
  it('creates a list', async () => {
    const list = await createList('My List');
    expect(list.name).toBe('My List');
    expect(list.id).toBeDefined();
  });

  it('retrieves all non-system lists', async () => {
    await createList('Another List');
    const lists = await getLists();
    const userLists = lists.filter((l) => !l.isSystem);
    expect(userLists.length).toBeGreaterThanOrEqual(2);
  });
});