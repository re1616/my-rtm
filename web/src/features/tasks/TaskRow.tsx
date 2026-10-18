// Taskly Web — one task row (SPEC 4.2).
// Checkbox, priority stripe (also announced as text), name, tag chips, icons, due date.
// Click opens the card; Ctrl/⌘-click toggles selection; Shift-click selects a range.
import React from 'react';
import type { Task } from '../../types';

export interface TaskRowProps {
  task: Task;
  isSelected: boolean;
  isCursor: boolean;
  isOpen: boolean;
  subtasks?: { done: number; total: number };
  hasNotes?: boolean;
  hasAttachments?: boolean;
  locationName?: string;
  onClick: (id: string, e: React.MouseEvent) => void;
  onToggleComplete: (id: string) => void;
  onPostpone: (id: string) => void;
}

export function formatDue(task: Pick<Task, 'dueDate' | 'dueHasTime' | 'completedAt'>, now = new Date()): { text: string; tone: 'overdue' | 'today' | 'tomorrow' | 'future' | '' } {
  if (!task.dueDate) return { text: '', tone: '' };
  const due = new Date(task.dueDate);
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = new Date(due.getFullYear(), due.getMonth(), due.getDate()).getTime();
  const diff = Math.round((day - today) / 86_400_000);
  const time = task.dueHasTime ? ' ' + due.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '';
  let text: string;
  if (diff === 0) text = 'Сегодня' + time;
  else if (diff === 1) text = 'Завтра' + time;
  else if (diff === -1) text = 'Вчера' + time;
  else text = due.toLocaleDateString(undefined, { day: 'numeric', month: 'short', ...(due.getFullYear() !== now.getFullYear() ? { year: 'numeric' } : {}) }) + time;
  const overdue = !task.completedAt && (task.dueHasTime ? due.getTime() < now.getTime() : diff < 0);
  return { text, tone: overdue ? 'overdue' : diff === 0 ? 'today' : diff === 1 ? 'tomorrow' : 'future' };
}

const PRIORITY_TEXT: Record<number, string> = { 1: 'приоритет 1', 2: 'приоритет 2', 3: 'приоритет 3' };

const TaskRow: React.FC<TaskRowProps> = ({
  task,
  isSelected,
  isCursor,
  isOpen,
  subtasks,
  hasNotes,
  hasAttachments,
  locationName,
  onClick,
  onToggleComplete,
  onPostpone,
}) => {
  const due = formatDue(task);
  const done = !!task.completedAt;
  const cls =
    'task-row' +
    (isSelected ? ' task-row--selected' : '') +
    (isCursor ? ' task-row--cursor' : '') +
    (isOpen ? ' task-row--open' : '') +
    (done ? ' task-row--completed' : '') +
    (task.priority ? ' task-row--priority-' + task.priority : '');

  return (
    <div
      className={cls}
      role="listitem"
      tabIndex={isCursor ? 0 : -1}
      aria-current={isCursor ? 'true' : undefined}
      data-task-id={task.id}
      id={`task-${task.id}`}
      onClick={(e) => onClick(task.id, e)}
    >
      {isSelected && <span className="visually-hidden">Выделена. </span>}
      <button
        type="button"
        className={`task-row-check${done ? ' task-row-check--done' : ''}`}
        aria-label={done ? `Вернуть «${task.name}» в работу` : `Выполнить «${task.name}»`}
        aria-pressed={done}
        onClick={(e) => {
          e.stopPropagation();
          onToggleComplete(task.id);
        }}
      >
        {done ? '✓' : ''}
      </button>
      <div className="task-row-body">
        <span className={'task-row-name' + (done ? ' task-row-name--done' : '')}>{task.name}</span>
        {task.priority && <span className="visually-hidden">, {PRIORITY_TEXT[task.priority]}</span>}
        <span className="task-row-meta">
          {subtasks && subtasks.total > 0 && (
            <span className="task-row-subtasks" title="Подзадачи">
              {subtasks.done} из {subtasks.total}
            </span>
          )}
          {task.tags.map((t) => (
            <span key={t} className="task-row-tag">
              #{t}
            </span>
          ))}
          {task.repeatRule && <span className="task-row-icon" title="Повторяется" aria-label="повторяется">🔁</span>}
          {hasNotes && <span className="task-row-icon" title="Есть заметки" aria-label="есть заметки">📝</span>}
          {hasAttachments && <span className="task-row-icon" title="Есть вложения" aria-label="есть вложения">📎</span>}
          {locationName && <span className="task-row-location" title="Место">📍 {locationName}</span>}
          {due.text && <span className={`task-row-due task-row-due--${due.tone}`}>{due.text}</span>}
        </span>
      </div>
      <span className="task-row-hover" onClick={(e) => e.stopPropagation()}>
        {!done && (
          <button type="button" className="task-row-action" onClick={() => onPostpone(task.id)} title="Отложить на день (P)">
            Отложить
          </button>
        )}
      </span>
    </div>
  );
};

export default React.memo(TaskRow);
