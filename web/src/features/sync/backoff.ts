// Taskly Web — retry delays for the sync loop (WEB-S2).

export const BACKOFF_BASE_MS = 1_000;
export const BACKOFF_MAX_MS = 5 * 60_000;

/**
 * Exponential backoff with "equal jitter": attempt 0 waits 0.5–1 s, then the ceiling doubles
 * up to 5 minutes; half of each delay is random so many clients do not retry in lockstep.
 */
export function backoffDelay(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** Math.max(0, attempt));
  return Math.round(ceiling / 2 + random() * (ceiling / 2));
}
