// Taskly Web — keyboard control (SPEC 4.3, WEB-F4).
//
// Single-letter keys work whenever the focus is not in a text field. They are matched by
// physical key (KeyboardEvent.code), so they also work with the Russian layout.
import store from '../../store/taskStore';
import {
  completeTargets,
  deleteTargets,
  postponeTargets,
  setPriorityTargets,
  targetIds,
} from '../tasks/actions';
import { undoLast } from '../undo/undo';
import { msg } from '../../i18n/i18n';

export interface Shortcut {
  keys: string;
  description: string;
}

/** Shown by "?" — the same table as SPEC 4.3. */
export const SHORTCUTS: Shortcut[] = [
  { keys: 'T', description: msg('Новая задача (фокус в Smart Add)') },
  { keys: 'C', description: msg('Выполнить выделенные (на выполненных — вернуть в работу)') },
  { keys: 'P', description: msg('Отложить на день') },
  { keys: '1 2 3 4', description: msg('Приоритет 1 / 2 / 3 / без приоритета') },
  { keys: 'D', description: msg('Срок') },
  { keys: 'S', description: msg('Добавить теги') },
  { keys: 'R', description: msg('Переименовать') },
  { keys: 'F', description: msg('Повтор') },
  { keys: 'M', description: msg('Переместить в список') },
  { keys: 'J / K', description: msg('Следующая / предыдущая задача') },
  { keys: 'I', description: msg('Выделить / снять выделение') },
  { keys: 'A / N', description: msg('Выделить все / ничего') },
  { keys: 'Enter', description: msg('Открыть карточку задачи') },
  { keys: '/', description: msg('Поиск') },
  { keys: 'Ctrl+Z', description: msg('Отменить последнее действие') },
  { keys: 'Delete', description: msg('Удалить') },
  { keys: 'Esc', description: msg('Закрыть карточку / снять выделение') },
  { keys: '?', description: msg('Эта шпаргалка') },
];

export function isTypingTarget(el: Element | null): boolean {
  if (!el) return false;
  const tag = el.tagName.toLowerCase();
  if (tag === 'textarea' || tag === 'select') return true;
  if (tag === 'input') {
    const type = (el as HTMLInputElement).type;
    return !['checkbox', 'radio', 'button', 'submit'].includes(type);
  }
  return (el as HTMLElement).isContentEditable || el.getAttribute('role') === 'textbox';
}

function focusSelector(selector: string): void {
  const el = document.querySelector<HTMLInputElement>(selector);
  if (el) {
    el.focus();
    el.select?.();
  }
}

function moveCursor(delta: 1 | -1): void {
  const s = store.getState();
  const ids = s.tasks.map((t) => t.id);
  if (!ids.length) return;
  const i = s.cursorId ? ids.indexOf(s.cursorId) : -1;
  const next = i < 0 ? (delta > 0 ? 0 : ids.length - 1) : Math.min(Math.max(i + delta, 0), ids.length - 1);
  s.setCursor(ids[next]);
  // Keep the card on the task under the pointer when the card is open
  if (s.openTaskId) s.openTask(ids[next]);
  // Real focus on the row: screen readers announce the task, the browser scrolls to it
  requestAnimationFrame(() => document.getElementById(`task-${ids[next]}`)?.focus());
}

function needTargets(open: () => void): void {
  if (targetIds().length) open();
}

/** Returns true when the key was handled. Exported for tests. */
export function handleKey(e: KeyboardEvent): boolean {
  const s = store.getState();
  const typing = isTypingTarget(document.activeElement);

  if (e.key === 'Escape') {
    if (s.dialog) {
      s.setDialog(null);
      return true;
    }
    if (typing) {
      (document.activeElement as HTMLElement).blur();
      return true;
    }
    if (s.selectedTaskIds.length) s.clearSelection();
    else s.openTask(null);
    return true;
  }
  if (s.dialog || typing) return false;

  if ((e.ctrlKey || e.metaKey) && !e.altKey && e.code === 'KeyZ' && !e.shiftKey) {
    void undoLast();
    return true;
  }
  if (e.ctrlKey || e.metaKey || e.altKey) return false;

  if (e.key === '?' || (e.shiftKey && e.code === 'Slash')) {
    s.setDialog('shortcuts');
    return true;
  }
  if (e.shiftKey) return false;

  switch (e.code) {
    case 'KeyT':
      focusSelector('.smartadd-input--primary');
      return true;
    case 'Slash':
      focusSelector('.topbar-search-input');
      return true;
    case 'KeyJ':
      moveCursor(1);
      return true;
    case 'KeyK':
      moveCursor(-1);
      return true;
    case 'Enter': {
      // Enter on a button or link keeps its native meaning
      const tag = document.activeElement?.tagName.toLowerCase();
      if (tag === 'button' || tag === 'a') return false;
      if (s.cursorId) s.openTask(s.cursorId);
      return !!s.cursorId;
    }
    case 'KeyI':
      if (s.cursorId) s.toggleTaskSelection(s.cursorId);
      return true;
    case 'KeyA':
      s.setSelectedTaskIds(s.tasks.map((t) => t.id));
      return true;
    case 'KeyN':
      s.clearSelection();
      return true;
    case 'KeyC':
      void completeTargets();
      return true;
    case 'KeyP':
      void postponeTargets();
      return true;
    case 'Digit1':
    case 'Digit2':
    case 'Digit3':
    case 'Numpad1':
    case 'Numpad2':
    case 'Numpad3':
      void setPriorityTargets(Number(e.code.slice(-1)) as 1 | 2 | 3);
      return true;
    case 'Digit4':
    case 'Numpad4':
      void setPriorityTargets(null);
      return true;
    case 'KeyD':
      needTargets(() => s.setDialog('due'));
      return true;
    case 'KeyS':
      needTargets(() => s.setDialog('tags'));
      return true;
    case 'KeyR':
      needTargets(() => s.setDialog('rename'));
      return true;
    case 'KeyF':
      needTargets(() => s.setDialog('repeat'));
      return true;
    case 'KeyM':
      needTargets(() => s.setDialog('move'));
      return true;
    case 'Delete':
      void deleteTargets();
      return true;
    default:
      return false;
  }
}

function onKeyDown(e: KeyboardEvent): void {
  if (e.defaultPrevented || e.isComposing) return;
  if (handleKey(e)) e.preventDefault();
}

let installed = false;

export function initHotkeys(): void {
  if (installed) return;
  window.addEventListener('keydown', onKeyDown);
  installed = true;
}

export function destroyHotkeys(): void {
  if (!installed) return;
  window.removeEventListener('keydown', onKeyDown);
  installed = false;
}
