// Taskly Web — Task Card (detail view stub)
// Renders all task properties: name, priority, due, list, tags, subtasks, notes, attachments, history
import React from 'react';
import type { Task } from '../../types';

interface TaskCardProps {
  task: Task;
  onClose: () => void;
}

const TaskCard: React.FC<TaskCardProps> = ({ task, onClose }) => {
  return (
    <div className="task-card">
      <div className="task-card-header">
        <h3 className="task-card-title">{task.name}</h3>
        <button className="task-card-close" onClick={onClose} title="Закрыть">
          &times;
        </button>
      </div>

      <div className="task-card-body">
        {/* Priority */}
        <div className="task-card-field">
          <span className="task-card-label">Приоритет</span>
          <span className={`task-card-value priority-badge priority-${task.priority || 'none'}`}>
            {task.priority ? `!${task.priority}` : '—'}
          </span>
        </div>

        {/* Due date */}
        <div className="task-card-field">
          <span className="task-card-label">Срок</span>
          <span className="task-card-value">
            {task.dueDate ? new Date(task.dueDate).toLocaleDateString() : '—'}
          </span>
        </div>

        {/* List */}
        <div className="task-card-field">
          <span className="task-card-label">Список</span>
          <span className="task-card-value">{task.listId.replace('sys-', '')}</span>
        </div>

        {/* Tags */}
        <div className="task-card-field">
          <span className="task-card-label">Теги</span>
          <div className="task-card-tags">
            {task.tags.length > 0
              ? task.tags.map((t) => (
                  <span key={t} className="task-card-tag">
                    #{t}
                  </span>
                ))
              : <span className="task-card-value">—</span>}
          </div>
        </div>

        {/* Repeat */}
        <div className="task-card-field">
          <span className="task-card-label">Повтор</span>
          <span className="task-card-value">{task.repeatRule || '—'}</span>
        </div>

        {/* Estimate */}
        <div className="task-card-field">
          <span className="task-card-label">Оценка</span>
          <span className="task-card-value">
            {task.estimateMin ? `${task.estimateMin} мин` : '—'}
          </span>
        </div>

        {/* Subtasks placeholder */}
        <div className="task-card-field">
          <span className="task-card-label">Подзадачи</span>
          <div className="task-card-subtasks-stub">
            <span className="task-card-value">Подзадачи будут доступны в R2</span>
          </div>
        </div>

        {/* Notes */}
        <div className="task-card-field">
          <span className="task-card-label">Заметки</span>
          <div className="task-card-notes-stub">
            {task.notes || 'Нет заметок'}
          </div>
        </div>

        {/* Attachments placeholder */}
        <div className="task-card-field">
          <span className="task-card-label">Вложения</span>
          <div className="task-card-attachments-stub">
            <span className="task-card-value">Вложения будут доступны в R2</span>
          </div>
        </div>

        {/* History */}
        <div className="task-card-field">
          <span className="task-card-label">История</span>
          <div className="task-card-history">
            {task.createdAt && (
              <div className="task-card-history-item">
                Создано: {new Date(task.createdAt).toLocaleString()}
              </div>
            )}
            {task.updatedAt && (
              <div className="task-card-history-item">
                Обновлено: {new Date(task.updatedAt).toLocaleString()}
              </div>
            )}
            {task.completedAt && (
              <div className="task-card-history-item">
                Завершено: {new Date(task.completedAt).toLocaleString()}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default TaskCard;