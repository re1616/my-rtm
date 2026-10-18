// Taskly Web — task actions shared by hotkeys, the mass-actions bar, rows and the card
// (WEB-F4). Each action works on the selection, or on the task under the keyboard pointer,
// writes through the repository (local-first + sync queue), refreshes the UI and offers undo.
import store from '../../store/taskStore';
import type { Task } from '../../types';
import {
  addTagsToTask,
  completeTask,
  createLocation,
  createTask,
  findListByName,
  getTaskById,
  INBOX_ID,
  postponeTask,
  removeTagsFromTask,
  restoreTask,
  softDeleteTask,
  uncompleteTask,
  updateTask,
} from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import { offerUndo } from '../undo/undo';
import { parseSmartAdd } from '../smartadd/parser';
import type { SmartAddResult } from '../../types';
import { defaultListFor } from '../app/view';

/** Selected tasks, otherwise the task under the pointer, otherwise the open card. */
export function targetIds(): string[] {
  const s = store.getState();
  if (s.selectedTaskIds.length) return [...s.selectedTaskIds];
  const one = s.cursorId ?? s.openTaskId;
  return one ? [one] : [];
}

function tasksById(ids: string[]): Task[] {
  const all = new Map(store.getState().allTasks.map((t) => [t.id, t]));
  return ids.map((id) => all.get(id)).filter((t): t is Task => !!t);
}

const plural = (n: number) => (n === 1 ? 'задача' : n < 5 ? 'задачи' : 'задач');

type Fields = Partial<Pick<Task, 'name' | 'listId' | 'priority' | 'dueDate' | 'dueHasTime' | 'repeatRule' | 'repeatMode' | 'postponedCount'>>;

/** Applies [change] to each task and offers to put the previous values back. */
async function updateMany(ids: string[], change: (t: Task) => Fields, label: string): Promise<void> {
  const before = tasksById(ids);
  if (!before.length) return;
  const reverts: Array<[string, Fields]> = [];
  for (const t of before) {
    const fields = change(t);
    const old: Fields = {};
    for (const k of Object.keys(fields) as (keyof Fields)[]) (old as Record<string, unknown>)[k] = t[k] ?? null;
    reverts.push([t.id, normalizeNulls(old)]);
    await updateTask(t.id, fields);
  }
  await refreshData();
  offerUndo(`${label}: ${before.length} ${plural(before.length)}`, async () => {
    for (const [id, old] of reverts) await updateTask(id, old);
    await refreshData();
  });
}

/** Dexie keeps "no value" as a missing field; the wire format sends null to clear. */
function normalizeNulls(f: Fields): Fields {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(f)) out[k] = v === null && k !== 'priority' ? undefined : v;
  return out as Fields;
}

// ── Actions ──────────────────────────────────────────────────────────────

/** C / checkbox: completes open tasks; on already completed ones it reopens them. */
export async function completeTargets(ids = targetIds()): Promise<void> {
  const tasks = tasksById(ids);
  if (!tasks.length) return;
  const toReopen = tasks.filter((t) => t.completedAt).map((t) => t.id);
  const toComplete = tasks.filter((t) => !t.completedAt).map((t) => t.id);
  for (const id of toComplete) await completeTask(id);
  for (const id of toReopen) await uncompleteTask(id);
  store.getState().clearSelection();
  await refreshData();
  const label = toComplete.length ? 'Выполнено' : 'Снова открыто';
  offerUndo(`${label}: ${tasks.length} ${plural(tasks.length)}`, async () => {
    for (const id of toComplete) await uncompleteTask(id);
    for (const id of toReopen) await completeTask(id);
    await refreshData();
  });
}

/** P: postpone by a day (FR-3). */
export async function postponeTargets(ids = targetIds()): Promise<void> {
  const before = tasksById(ids);
  if (!before.length) return;
  for (const t of before) await postponeTask(t.id);
  await refreshData();
  offerUndo(`Отложено: ${before.length} ${plural(before.length)}`, async () => {
    for (const t of before) {
      await updateTask(t.id, { dueDate: t.dueDate, dueHasTime: t.dueHasTime, postponedCount: t.postponedCount ?? 0 });
    }
    await refreshData();
  });
}

/** 1/2/3/4: priority; 4 = no priority. */
export function setPriorityTargets(priority: 1 | 2 | 3 | null, ids = targetIds()): Promise<void> {
  return updateMany(ids, () => ({ priority }), priority ? `Приоритет ${priority}` : 'Без приоритета');
}

export async function deleteTargets(ids = targetIds()): Promise<void> {
  const tasks = tasksById(ids);
  if (!tasks.length) return;
  for (const t of tasks) await softDeleteTask(t.id);
  const s = store.getState();
  s.clearSelection();
  if (s.openTaskId && ids.includes(s.openTaskId)) s.openTask(null);
  await refreshData();
  offerUndo(`Удалено: ${tasks.length} ${plural(tasks.length)}`, async () => {
    for (const t of tasks) await restoreTask(t.id);
    await refreshData();
  });
}

export function moveTargets(listId: string, ids = targetIds()): Promise<void> {
  return updateMany(ids, () => ({ listId }), 'Перемещено');
}

export async function addTagsTargets(tags: string[], ids = targetIds()): Promise<void> {
  const before = tasksById(ids);
  const clean = tags.map((t) => t.trim().replace(/^#/, '')).filter(Boolean);
  if (!before.length || !clean.length) return;
  const added = new Map(before.map((t) => [t.id, clean.filter((x) => !t.tags.includes(x))]));
  for (const t of before) await addTagsToTask(t.id, clean);
  await refreshData();
  offerUndo(`Теги добавлены: ${before.length} ${plural(before.length)}`, async () => {
    for (const [id, tags2] of added) if (tags2.length) await removeTagsFromTask(id, tags2);
    await refreshData();
  });
}

export async function removeTagsTargets(tags: string[], ids = targetIds()): Promise<void> {
  const before = tasksById(ids);
  const clean = tags.map((t) => t.trim().replace(/^#/, '')).filter(Boolean);
  if (!before.length || !clean.length) return;
  const removed = new Map(before.map((t) => [t.id, clean.filter((x) => t.tags.includes(x))]));
  for (const t of before) await removeTagsFromTask(t.id, clean);
  await refreshData();
  offerUndo('Теги удалены', async () => {
    for (const [id, tags2] of removed) if (tags2.length) await addTagsToTask(id, tags2);
    await refreshData();
  });
}

/** Parses "завтра 17:00", "пт", "25 дек"… with the shared Smart Add parser. Empty = no due date. */
export function parseDue(text: string): { dueDate?: string; dueHasTime: boolean } | null {
  if (!text.trim()) return { dueDate: undefined, dueHasTime: false };
  const r = parseSmartAdd(`x ^${text.trim()}`);
  return r.dueDate ? { dueDate: r.dueDate, dueHasTime: !!r.dueHasTime } : null;
}

/** Parses "каждую неделю", "after 2 weeks"… Empty = no repeat. */
export function parseRepeat(text: string): { repeatRule?: string; repeatMode?: Task['repeatMode'] } | null {
  if (!text.trim()) return { repeatRule: undefined, repeatMode: undefined };
  const r = parseSmartAdd(`x *${text.trim()}`);
  return r.repeatRule ? { repeatRule: r.repeatRule, repeatMode: r.repeatMode ?? 'every' } : null;
}

/** D: due date. Returns false when the text is not a date. */
export async function setDueTargets(text: string, ids = targetIds()): Promise<boolean> {
  const due = parseDue(text);
  if (!due) return false;
  await updateMany(ids, () => due, due.dueDate ? 'Срок изменён' : 'Срок снят');
  return true;
}

/** F: repeat. Returns false when the text is not a repeat rule. */
export async function setRepeatTargets(text: string, ids = targetIds()): Promise<boolean> {
  const rep = parseRepeat(text);
  if (!rep) return false;
  await updateMany(ids, () => rep, rep.repeatRule ? 'Повтор задан' : 'Повтор снят');
  return true;
}

/** R: rename one task. */
export async function renameTask(id: string, name: string): Promise<void> {
  const trimmed = name.trim();
  if (!trimmed) return;
  await updateMany([id], () => ({ name: trimmed }), 'Переименовано');
}

/**
 * Smart Add (WEB-F1, scenario 1): creates the task in "#list" (or the list on screen), with
 * every recognized property. In "Сегодня"/"Завтра" a task without "^due" gets that day.
 */
export async function createFromSmartAdd(result: SmartAddResult, opts: { parentId?: string } = {}): Promise<Task | null> {
  if (!result.name.trim()) return null;
  const s = store.getState();
  let listId = defaultListFor(s.currentView);
  const tags = [...result.tags];
  if (result.listName) {
    // Autocomplete writes multi-word names with "_" (Smart Add tokens are single words)
    const list = (await findListByName(result.listName)) ?? (await findListByName(result.listName.replace(/_/g, ' ')));
    if (list) listId = list.id;
    else if (/^(inbox|входящие)$/i.test(result.listName)) listId = INBOX_ID;
    // "#word" that is not a list is a new tag (SPEC F-2: a list wins only on a match)
    else if (!tags.includes(result.listName)) tags.unshift(result.listName);
  }
  let dueDate = result.dueDate;
  let dueHasTime = result.dueHasTime ?? false;
  if (!dueDate && !opts.parentId && s.currentView.kind === 'system' && (s.currentView.view === 'today' || s.currentView.view === 'tomorrow')) {
    const n = new Date();
    dueDate = new Date(n.getFullYear(), n.getMonth(), n.getDate() + (s.currentView.view === 'tomorrow' ? 1 : 0)).toISOString();
    dueHasTime = false;
  }
  let locationId: string | undefined;
  if (result.location) {
    const name = result.location.trim();
    const known = s.locations.find((l) => l.name.toLowerCase() === name.toLowerCase());
    locationId = known ? known.id : (await createLocation(name)).id;
  }
  if (opts.parentId) {
    const parent = await getTaskById(opts.parentId);
    if (parent && !result.listName) listId = parent.listId;
  }
  if (s.currentView.kind === 'tag' && !tags.includes(s.currentView.tag)) tags.push(s.currentView.tag);
  const task = await createTask({
    name: result.name.trim(),
    listId,
    parentId: opts.parentId,
    locationId,
    priority: (result.priority ?? null) as Task['priority'],
    dueDate,
    dueHasTime,
    startDate: result.startDate,
    tags,
    repeatRule: result.repeatRule,
    repeatMode: result.repeatMode,
    estimateMin: result.estimateMin,
    note: result.note,
  });
  await refreshData();
  offerUndo('Задача добавлена', async () => {
    await softDeleteTask(task.id);
    await refreshData();
  });
  return task;
}
