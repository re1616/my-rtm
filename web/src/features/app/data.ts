// Taskly Web — loads local data into the store and keeps the visible list in sync.
import store from '../../store/taskStore';
import { getAllTasks, getLists, getLocations, getTagNames } from '../../entities/taskRepository';
import { getDb } from '../../db/database';
import { computeView } from './view';

let pending: Promise<void> | null = null;
let again = false;

/** Reloads tasks, lists, tags and locations from IndexedDB. Concurrent calls are coalesced. */
export function refreshData(): Promise<void> {
  if (pending) {
    again = true;
    return pending;
  }
  pending = (async () => {
    do {
      again = false;
      const [allTasks, lists, tagNames, locations, notes, attachments] = await Promise.all([
        getAllTasks(),
        getLists(),
        getTagNames(),
        getLocations(),
        getDb().notes.toArray(),
        getDb().attachments.toArray(),
      ]);
      store.getState().setData({
        allTasks,
        lists,
        tagNames,
        locations,
        noteCounts: countBy(notes.map((n) => n.taskId)),
        attachmentCounts: countBy(attachments.map((a) => a.taskId)),
      });
      recomputeVisible();
    } while (again);
  })().finally(() => {
    pending = null;
  });
  return pending;
}

/** Derives `tasks` (the current view) from the loaded data; drops stale selection and card. */
export function recomputeVisible(): void {
  const s = store.getState();
  const { tasks } = computeView({
    view: s.currentView,
    tab: s.tab,
    allTasks: s.allTasks,
    lists: s.lists,
    locations: s.locations,
    sortBy: s.sortBy,
    sortAsc: s.sortAsc,
  });
  s.setTasks(tasks);
  const live = new Set(s.allTasks.map((t) => t.id));
  const visible = new Set(tasks.map((t) => t.id));
  const selected = s.selectedTaskIds.filter((id) => visible.has(id));
  if (selected.length !== s.selectedTaskIds.length) s.setSelectedTaskIds(selected);
  if (s.cursorId && !visible.has(s.cursorId)) s.setCursor(null);
  if (s.openTaskId && !live.has(s.openTaskId)) s.openTask(null);
}

/** Re-derive the list whenever navigation, tab or sorting changes. */
let installed = false;
export function installViewRecompute(): void {
  if (installed) return;
  installed = true;
  store.subscribe((s, prev) => {
    if (s.currentView !== prev.currentView || s.tab !== prev.tab || s.sortBy !== prev.sortBy || s.sortAsc !== prev.sortAsc) {
      recomputeVisible();
    }
  });
}

function countBy(ids: string[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const id of ids) out[id] = (out[id] ?? 0) + 1;
  return out;
}
