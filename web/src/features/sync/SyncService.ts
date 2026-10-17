// Taskly Web — Sync Service (UI-side orchestrator)
// Monitors network status and processes the sync queue.
// Communicates with the Web Worker for background sync.

import { getPendingSyncOps, markSyncOpStatus } from '../../entities/taskRepository';

const SYNC_INTERVAL_MS = 15000; // 15 seconds
const SERVER_HEALTH_URL = '/health'; // relative to API origin

let syncTimer: ReturnType<typeof setInterval> | null = null;
let isRunning = false;

/**
 * Check if the server is reachable.
 * In production this would ping the actual server.
 */
async function checkServerOnline(): Promise<boolean> {
  try {
    // Use the current origin to check if we're online
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);

    const response = await fetch(SERVER_HEALTH_URL, {
      method: 'HEAD',
      signal: controller.signal as AbortSignal,
    });
    clearTimeout(timeout);
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Process the sync queue: send pending operations to the server.
 * For now this simulates the sync process since there's no real server yet.
 */
async function processSyncQueue(): Promise<void> {
  const pendingOps = await getPendingSyncOps();
  if (pendingOps.length === 0) return;

  for (const op of pendingOps) {
    try {
      await markSyncOpStatus(op.opId, 'sending');

      // In production: POST /sync/ops with the operation
      // For now, simulate sending

      // If the server is unreachable, keep as pending
      const online = await checkServerOnline();
      if (!online) {
        await markSyncOpStatus(op.opId, 'pending');
        return; // Stop processing until next cycle
      }

      // Simulate successful send
      await markSyncOpStatus(op.opId, 'sent');

      // Remove sent operations after 1 minute (simulate cleanup)
      // In production: the server confirms receipt
    } catch {
      // On failure, mark as failed for retry
      await markSyncOpStatus(op.opId, 'failed');
    }
  }
}

/**
 * Start the sync loop.
 * Call when the app initializes.
 */
export function startSyncService(): void {
  if (isRunning) return;
  isRunning = true;

  // Immediate first check
  processSyncQueue();

  // Periodic polling
  syncTimer = setInterval(() => {
    processSyncQueue();
  }, SYNC_INTERVAL_MS);
}

/**
 * Stop the sync loop.
 * Call during cleanup/shutdown.
 */
export function stopSyncService(): void {
  if (syncTimer) {
    clearInterval(syncTimer);
    syncTimer = null;
  }
  isRunning = false;
}

/**
 * Manually trigger a sync cycle.
 * Called after creating a task offline.
 */
export async function triggerSync(): Promise<void> {
  await processSyncQueue();
}

export { checkServerOnline };