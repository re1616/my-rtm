// Taskly Web — undo of the last action within 10 s (SPEC FR-6): a toast with "Отменить"
// and Ctrl+Z. Each action registers how to revert itself.
import store from '../../store/taskStore';

export const UNDO_WINDOW_MS = 10_000;

interface Entry {
  id: number;
  label: string;
  revert: () => Promise<void>;
  expires: number;
}

let last: Entry | null = null;
let timer: ReturnType<typeof setTimeout> | null = null;
let nextId = 1;

export function offerUndo(label: string, revert: () => Promise<void>): void {
  if (timer) clearTimeout(timer);
  last = { id: nextId++, label, revert, expires: Date.now() + UNDO_WINDOW_MS };
  store.getState().setToast({ id: last.id, label });
  timer = setTimeout(dismissUndo, UNDO_WINDOW_MS);
}

export function dismissUndo(): void {
  if (timer) clearTimeout(timer);
  timer = null;
  last = null;
  store.getState().setToast(null);
}

/** Reverts the last action if it is still within the undo window. Returns false if nothing to undo. */
export async function undoLast(): Promise<boolean> {
  const entry = last;
  if (!entry || Date.now() > entry.expires) {
    dismissUndo();
    return false;
  }
  dismissUndo();
  await entry.revert();
  return true;
}
