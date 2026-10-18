// Taskly Web — Keyboard Shortcuts (scaffold)
// Defines app-wide keyboard shortcuts and provides setup/teardown

export interface HotkeyBinding {
  key: string;
  ctrl?: boolean;
  meta?: boolean;
  shift?: boolean;
  alt?: boolean;
  description: string;
  handler: (e: KeyboardEvent) => void;
}

const DEFAULT_BINDINGS: HotkeyBinding[] = [
  {
    key: 'n',
    ctrl: true,
    description: 'Новая задача',
    handler: () => {
      // Focus the Smart Add input
      const input = document.querySelector<HTMLInputElement>('.smartadd-input');
      if (input) {
        input.focus();
        input.select();
      }
    },
  },
  {
    key: 'Escape',
    description: 'Закрыть / Отмена',
    handler: () => {
      // Close detail panel, clear selection
      window.dispatchEvent(
        new CustomEvent('taskly-hotkey', { detail: { action: 'close' } }),
      );
      // Blur any focused input
      if (document.activeElement instanceof HTMLElement) {
        document.activeElement.blur();
      }
    },
  },
  {
    key: 'a',
    ctrl: true,
    description: 'Выбрать все',
    handler: () => {
      // In production: select all visible tasks
      window.dispatchEvent(
        new CustomEvent('taskly-hotkey', { detail: { action: 'select-all' } }),
      );
    },
  },
  {
    key: 'Delete',
    description: 'Удалить выбранные',
    handler: () => {
      window.dispatchEvent(
        new CustomEvent('taskly-hotkey', { detail: { action: 'delete-selected' } }),
      );
    },
  },
  {
    key: '/',
    ctrl: true,
    description: 'Поиск',
    handler: () => {
      // Focus search / filter
      window.dispatchEvent(
        new CustomEvent('taskly-hotkey', { detail: { action: 'search' } }),
      );
    },
  },
  {
    key: 's',
    ctrl: true,
    description: 'Фокус на Smart Add',
    handler: () => {
      const input = document.querySelector<HTMLInputElement>('.smartadd-input');
      if (input) {
        input.focus();
        input.select();
      }
    },
  },
  {
    key: '?',
    description: 'Показать шорткаты',
    handler: () => {
      window.dispatchEvent(
        new CustomEvent('taskly-hotkey', { detail: { action: 'show-shortcuts' } }),
      );
    },
  },
];

let isInitialized = false;
let activeBindings: HotkeyBinding[] = [];

function handleKeyDown(e: KeyboardEvent): void {
  for (const binding of activeBindings) {
    const keyMatch = e.key === binding.key;
    const ctrlMatch = !!binding.ctrl === (e.ctrlKey || e.metaKey);
    const shiftMatch = !!binding.shift === e.shiftKey;
    const altMatch = !!binding.alt === e.altKey;

    if (keyMatch && ctrlMatch && shiftMatch && altMatch) {
      e.preventDefault();
      e.stopPropagation();
      binding.handler(e);
      return;
    }
  }
}

/**
 * Initialize hotkey handling.
 * Call once when the app mounts.
 */
export function initHotkeys(bindings?: HotkeyBinding[]): void {
  if (isInitialized) return;
  activeBindings = bindings || DEFAULT_BINDINGS;
  window.addEventListener('keydown', handleKeyDown);
  isInitialized = true;
}

/**
 * Tear down hotkey handling.
 * Call when the app unmounts.
 */
export function destroyHotkeys(): void {
  if (!isInitialized) return;
  window.removeEventListener('keydown', handleKeyDown);
  activeBindings = [];
  isInitialized = false;
}

/**
 * Get the current list of active hotkey bindings.
 */
export function getBindings(): HotkeyBinding[] {
  return [...activeBindings];
}

/**
 * Get the default bindings for display in the shortcuts help.
 */
export function getDefaultBindings(): HotkeyBinding[] {
  return [...DEFAULT_BINDINGS];
}

export default { initHotkeys, destroyHotkeys, getBindings, getDefaultBindings };