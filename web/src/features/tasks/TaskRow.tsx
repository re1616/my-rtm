// Taskly Web — Single Task Row Component
import React from 'react';
import type { Task } from '../../types';

interface TaskRowProps {
  task: Task;
  isSelected: boolean;
  onToggleSelect: (id: string) => void;
  onComplete: (id: string) => void;
  onDelete: (id: string) => void;
  onClick?: (id: string) => void;
}

function formatDueDate(dateStr?: string): { text: string; className: string } {
  if (!dateStr) return { text: '', className: '' };
  const due = new Date(dateStr);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dueDay = new Date(due.getFullYear(), due.getMonth(), due.getDate());
  const diffMs = dueDay.getTime() - today.getTime();
  const diffDays = Math.round(diffMs / 86400000);

  const dayStr = due.toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
  });

  if (diffDays < 0) {
    return { text: dayStr + '\u274C', className: 'due-overdue' };
  }
  if (diffDays === 0) {
    return { text: '\uD83D\uDCC5 ' + dayStr, className: 'due-today' };
  }
  if (diffDays === 1) {
    return { text: '\uD83D\uDCC5 ' + dayStr, className: 'due-tomorrow' };
  }
  return { text: dayStr, className: 'due-future' };
}

const TaskRow: React.FC<TaskRowProps> = ({
  task,
  isSelected,
  onToggleSelect,
  onComplete,
  onDelete,
  onClick,
}) => {
  const due = formatDueDate(task.dueDate);
  const hasNotes = !!task.notes;
  const hasTags = task.tags && task.tags.length > 0;
  const isCompleted = !!task.completedAt;

  const rowClass =
    'task-row' +
    (isSelected ? ' task-row--selected' : '') +
    (isCompleted ? ' task-row--completed' : '') +
    (task.priority ? ' task-row--priority-' + task.priority : '');

  return (
    <div className={rowClass} onClick={() => onClick?.(task.id) ?? onToggleSelect(task.id)}>
      <div className="task-row-checkbox">
        <input
          type="checkbox"
          checked={isCompleted}
          onChange={(e) => {
            e.stopPropagation();
            onComplete(task.id);
          }}
        />
      </div>
      <div className="task-row-body">
        <span
          className={
            'task-row-name' + (isCompleted ? ' task-row-name--done' : '')
          }
        >
          {task.name}
        </span>
        <div className="task-row-meta">
          {task.priority && (
            <span className={'task-row-priority priority-' + task.priority}>
              {'!' + task.priority}
            </span>
          )}
          {due.text && (
            <span className={'task-row-due ' + due.className}>
              {due.text}
            </span>
          )}
          {hasTags && (
            <span className="task-row-tags">
              {task.tags.map((t) => (
                <span key={t} className="task-row-tag">
                  {'#' + t}
                </span>
              ))}
            </span>
          )}
          {hasNotes && <span className="task-row-note-indicator">📝</span>}
          {task.repeatRule && <span className="task-row-repeat-indicator">🔄</span>}
        </div>
      </div>
      <button
        className="task-row-delete"
        onClick={(e) => {
          e.stopPropagation();
          onDelete(task.id);
        }}
        title="Удалить"
      >
        ×
      </button>
    </div>
  );
};

export default TaskRow;