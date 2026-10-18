// Taskly Web — Keyboard Shortcuts (full implementation)
// All hotkeys from SPEC 4.3: T C P 1-4 D S R F M J/K I A/N / Ctrl+Z Delete ?
export interface HotkeyBinding {
  key: string; ctrl?: boolean; meta?: boolean; shift?: boolean; alt?: boolean;
  description: string; handler: (e: KeyboardEvent) => void;
}

const DEFAULT_BINDINGS: HotkeyBinding[] = [
  { key: 'i', description: 'Входящие', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'navigate', view: { kind: 'system', view: 'inbox' } } })); } },
  { key: 't', description: 'Сегодня (Today)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'navigate', view: { kind: 'system', view: 'today' } } })); } },
  { key: 'c', description: 'Выполненные (Completed)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'navigate', view: { kind: 'system', view: 'completed' } } })); } },
  { key: 's', description: 'Умные списки (Smart lists)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-navigate', { detail: { kind: 'smart-lists' } })); } },
  { key: 'n', ctrl: true, description: 'Новая задача', handler: () => { const input = document.querySelector<HTMLInputElement>('.smartadd-input'); if(input){input.focus();input.select();} } },
  { key: 'a', ctrl: true, description: 'Выбрать все', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'select-all' } })); } },
  { key: 'Escape', description: 'Закрыть / Отмена', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'close' } })); if(document.activeElement instanceof HTMLElement) document.activeElement.blur(); } },
  { key: '1', description: 'Приоритет !1', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'set-priority', priority: 1 } })); } },
  { key: '2', description: 'Приоритет !2', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'set-priority', priority: 2 } })); } },
  { key: '3', description: 'Приоритет !3', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'set-priority', priority: 3 } })); } },
  { key: '4', description: 'Без приоритета', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'set-priority', priority: null } })); } },
  { key: 'd', description: 'Срок (Due)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'focus-due' } })); } },
  { key: 'r', description: 'Повтор (Repeat)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'repeat' } })); } },
  { key: 'f', description: 'Фильтр / Поиск', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'search' } })); } },
  { key: 'm', description: 'Переместить (Move)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'move' } })); } },
  { key: '/', description: 'Поиск (/)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'search' } })); } },
  { key: 'j', description: 'Следующая задача', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'navigate-task', direction: 'next' } })); } },
  { key: 'k', description: 'Предыдущая задача', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'navigate-task', direction: 'prev' } })); } },
  { key: 'z', ctrl: true, description: 'Отмена (Undo)', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'undo' } })); } },
  { key: 'Delete', description: 'Удалить выбранные', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'delete-selected' } })); } },
  { key: '?', shift: true, description: 'Показать шорткаты', handler: () => { window.dispatchEvent(new CustomEvent('taskly-hotkey', { detail: { action: 'show-shortcuts' } })); } },
];

let isInitialized = false;
let activeBindings: HotkeyBinding[] = [];

function handleKeyDown(e: KeyboardEvent): void {
  for (const b of activeBindings) {
    if (e.key !== b.key) continue;
    if (!!b.ctrl !== (e.ctrlKey||e.metaKey)) continue;
    if (!!b.shift !== e.shiftKey) continue;
    if (!!b.alt !== e.altKey) continue;
    e.preventDefault(); e.stopPropagation(); b.handler(e); return;
  }
}

export function initHotkeys(bindings?: HotkeyBinding[]): void {
  if (isInitialized) return;
  activeBindings = bindings || DEFAULT_BINDINGS;
  window.addEventListener('keydown', handleKeyDown);
  isInitialized = true;
}

export function destroyHotkeys(): void {
  if (!isInitialized) return;
  window.removeEventListener('keydown', handleKeyDown);
  activeBindings = []; isInitialized = false;
}

export function getBindings(): HotkeyBinding[] { return [...activeBindings]; }
export function getDefaultBindings(): HotkeyBinding[] { return [...DEFAULT_BINDINGS]; }
export default { initHotkeys, destroyHotkeys, getBindings, getDefaultBindings };
