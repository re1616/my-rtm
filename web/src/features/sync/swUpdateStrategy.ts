// Taskly Web — app update flow (WEB-S8).
//
// A new Service Worker version is installed in the background and WAITS. The user sees
// "New version available — Update". Only on their click the waiting worker takes over and the
// page reloads — and never while they are typing: then the reload waits until focus leaves the
// field. Nothing reloads by itself, so no input is lost.

/** Signature of `registerSW` from `virtual:pwa-register` (vite-plugin-pwa). */
export type RegisterSW = (options: {
  immediate?: boolean;
  onNeedRefresh?: () => void;
  onOfflineReady?: () => void;
  onRegisterError?: (error: unknown) => void;
}) => (reloadPage?: boolean) => Promise<void>;

export type UpdateState = 'idle' | 'update-ready' | 'waiting-for-input' | 'updating';

export interface UpdateControllerDeps {
  isInputFocused?: () => boolean;
  /** Subscribes to "focus left the current field"; returns an unsubscribe function. */
  onInputBlur?: (fn: () => void) => () => void;
}

export class SwUpdateController {
  private _state: UpdateState = 'idle';
  private readonly listeners = new Set<(s: UpdateState) => void>();
  private readonly updateSW: (reloadPage?: boolean) => Promise<void>;
  private readonly inputFocused: () => boolean;
  private readonly onBlur: (fn: () => void) => () => void;

  constructor(register: RegisterSW, deps: UpdateControllerDeps = {}) {
    this.inputFocused = deps.isInputFocused ?? isInputFocused;
    this.onBlur = deps.onInputBlur ?? onDocumentFocusOut;
    this.updateSW = register({
      immediate: true,
      onNeedRefresh: () => this.setState('update-ready'),
    });
  }

  get state(): UpdateState {
    return this._state;
  }

  onChange(fn: (s: UpdateState) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /** The user accepted the update. */
  async apply(): Promise<void> {
    if (this._state !== 'update-ready') return;
    if (this.inputFocused()) {
      this.setState('waiting-for-input');
      await new Promise<void>((resolve) => {
        const off = this.onBlur(() => {
          if (this.inputFocused()) return;
          off();
          resolve();
        });
      });
    }
    this.setState('updating');
    // Activates the waiting worker and reloads once it controls the page
    await this.updateSW(true);
  }

  /** "Later": hide the prompt; the new version activates on the next visit. */
  dismiss(): void {
    if (this._state === 'update-ready') this.setState('idle');
  }

  private setState(s: UpdateState): void {
    this._state = s;
    for (const fn of this.listeners) fn(s);
  }
}

/** True while the user is typing in a field (reloading would lose the input). */
export function isInputFocused(): boolean {
  if (typeof document === 'undefined') return false;
  const el = document.activeElement as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName.toLowerCase();
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable || el.getAttribute('role') === 'textbox';
}

function onDocumentFocusOut(fn: () => void): () => void {
  // focusout fires before the next element is focused; check on the next tick
  const handler = () => setTimeout(fn, 0);
  document.addEventListener('focusout', handler);
  return () => document.removeEventListener('focusout', handler);
}
