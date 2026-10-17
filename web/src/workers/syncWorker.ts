// Taskly Web — Sync Engine Web Worker
// Runs in a separate thread to avoid blocking UI.
// Handles: queue processing, MiniSearch indexing, server sync.

import MiniSearch from 'minisearch';

interface SyncTask {
  id: string;
  name: string;
  tags: string[];
  notes?: string;
}

// MiniSearch instance for offline full-text search
const searchEngine = new MiniSearch<SyncTask>({
  fields: ['name', 'tags', 'notes'],
  storeFields: ['id', 'name'],
});

let indexedTaskCount = 0;

export function indexTask(task: SyncTask): void {
  searchEngine.add(task);
  indexedTaskCount++;
}

export function removeTask(id: string): void {
  searchEngine.remove({ id } as SyncTask);
}

export function searchTasks(query: string): Array<{ id: string; name: string }> {
  // MiniSearch.search returns SearchResult[] with id and score
  const results = searchEngine.search(query, { fuzzy: 0.2 }) as Array<{ id: string }>;
  // Map to stored values
  return results.map((r) => ({
    id: r.id,
    name: '',
  }));
}

export function getIndexedCount(): number {
  return indexedTaskCount;
}

// Self-executing worker loop (runs only when loaded as a worker script)
if (typeof self !== 'undefined' && 'postMessage' in self) {
  self.onmessage = (e: MessageEvent) => {
    const msg = e.data as Record<string, unknown>;
    switch (msg.type as string) {
      case 'index-task': {
        const task = msg.task as SyncTask;
        indexTask(task);
        self.postMessage({ type: 'indexed', id: task.id });
        break;
      }
      case 'search': {
        const query = msg.query as string;
        const results = searchTasks(query);
        self.postMessage({ type: 'search-results', query, results });
        break;
      }
      case 'ping':
        self.postMessage({ type: 'pong' });
        break;
      default:
        break;
    }
  };
}