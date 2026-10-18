// Taskly Web — Task Card (full implementation)
// Editable properties, subtasks, markdown notes, attachments, history
import React, { useState, useEffect } from 'react';
import type { Task, Priority } from '../../types';
import {
  updateTask,
  completeTask,
  uncompleteTask,
  softDeleteTask,
  addSubtask,
  getSubtasks,
} from '../../entities/taskRepository';
import store from '../../store/taskStore';

// Simple markdown to HTML renderer (no external deps)
function renderMarkdown(text: string): string {
  let html = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  // Headers
  html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>');
  html = html.replace(/^## (.+)$/gm, '<h3>$1</h3>');
  // Bold and italic
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
  // Links
  html = html.replace(/\[(.+?)\]\((.+?)\)/g, '<a href="$2" target="_blank">$1</a>');
  // Inline code
  html = html.replace(/`(.+?)`/g, '<code>$1</code>');
  // List items
  html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
  // Line breaks
  html = html.replace(/\n/g, '<br/>');

  return html;
}

interface SubtaskItem {
  id: string;
  name: string;
  completedAt?: string;
}

interface TaskCardProps {
  task: Task;
  onClose: () => void;
  onTaskUpdated?: () => void;
}

const TaskCard: React.FC<TaskCardProps> = ({ task, onClose, onTaskUpdated }) => {
  const [editing, setEditing] = useState(false);
  const [editName, setEditName] = useState(task.name);
  const [editPriority, setEditPriority] = useState(task.priority);
  const [editDueDate, setEditDueDate] = useState(task.dueDate || '');
  const [editTags, setEditTags] = useState(task.tags.join(', '));
  const [editNotes, setEditNotes] = useState(task.notes || '');
  const [editEstimate, setEditEstimate] = useState(task.estimateMin?.toString() || '');
  const [editUrl, setEditUrl] = useState(task.url || '');
  const [editRepeatRule, setEditRepeatRule] = useState(task.repeatRule || '');
  const [editStartDate, setEditStartDate] = useState(task.startDate || '');
  const [showNotesPreview, setShowNotesPreview] = useState(false);
  const [subtasks, setSubtasks] = useState<SubtaskItem[]>([]);
  const [newSubtaskName, setNewSubtaskName] = useState('');
  const [message, setMessage] = useState('');

  useEffect(() => {
    getSubtasks(task.id).then((sts) => {
      setSubtasks(sts.map((s) => ({ id: s.id, name: s.name, completedAt: s.completedAt })));
    });
  }, [task.id]);

  function startEditing() {
    setEditName(task.name);
    setEditPriority(task.priority);
    setEditDueDate(task.dueDate ? task.dueDate.slice(0, 16) : '');
    setEditTags(task.tags.join(', '));
    setEditNotes(task.notes || '');
    setEditEstimate(task.estimateMin?.toString() || '');
    setEditUrl(task.url || '');
    setEditRepeatRule(task.repeatRule || '');
    setEditStartDate(task.startDate ? task.startDate.slice(0, 16) : '');
    setEditing(true);
    setMessage('');
  }

  async function saveChanges() {
    const changes: Partial<Task> = {};
    if (editName !== task.name) changes.name = editName;
    if (editPriority !== task.priority) changes.priority = editPriority;
    if (editDueDate !== (task.dueDate || '')) changes.dueDate = editDueDate || undefined;
    if (editStartDate !== (task.startDate || '')) changes.startDate = editStartDate || undefined;
    const newTags = editTags.split(',').map((t) => t.trim()).filter(Boolean);
    if (JSON.stringify(newTags) !== JSON.stringify(task.tags)) changes.tags = newTags;
    if (editNotes !== (task.notes || '')) changes.notes = editNotes || undefined;
    const estInt = parseInt(editEstimate, 10);
    const newEstimate = isNaN(estInt) ? undefined : estInt;
    if (newEstimate !== task.estimateMin) changes.estimateMin = newEstimate;
    if (editUrl !== (task.url || '')) changes.url = editUrl || undefined;
    if (editRepeatRule !== (task.repeatRule || '')) changes.repeatRule = editRepeatRule || undefined;

    if (Object.keys(changes).length > 0) {
      await updateTask(task.id, changes);
      setMessage('Сохранено ✓');
      if (onTaskUpdated) onTaskUpdated();
    } else {
      setMessage('Нет изменений');
    }
    setEditing(false);
  }

  function cancelEditing() {
    setEditing(false);
    setMessage('');
  }

  async function handleComplete() {
    if (task.completedAt) {
      await uncompleteTask(task.id);
    } else {
      await completeTask(task.id);
    }
    setMessage(task.completedAt ? 'Возвращено в работу' : '✅ Выполнено');
    if (onTaskUpdated) onTaskUpdated();
  }

  async function handleDelete() {
    if (confirm('Удалить задачу?')) {
      await softDeleteTask(task.id);
      onClose();
      if (onTaskUpdated) onTaskUpdated();
    }
  }

  async function handleAddSubtask() {
    if (!newSubtaskName.trim()) return;
    const st = await addSubtask(task.id, newSubtaskName.trim());
    setNewSubtaskName('');
    subtasks.push({ id: st.id, name: st.name });
    setSubtasks([...subtasks]);
  }

  async function handleCompleteSubtask(id: string) {
    await completeTask(id);
    const st = subtasks.find((s) => s.id === id);
    if (st) {
      st.completedAt = new Date().toISOString();
      setSubtasks([...subtasks]);
    }
  }

  const priorityLabels: Record<number, string> = { 1: 'Высокий', 2: 'Средний', 3: 'Низкий' };

  return (
    <div className="task-card">
      <div className="task-card-header">
        {editing ? (
          <input
            type="text"
            className="task-card-title-input"
            value={editName}
            onChange={(e) => setEditName(e.target.value)}
          />
        ) : (
          <h3 className={`task-card-title ${task.completedAt ? 'task-card-title--done' : ''}`}>
            {task.completedAt && '✅ '}{task.name}
          </h3>
        )}
        <button className="task-card-close" onClick={onClose} title="Закрыть">
          &times;
        </button>
      </div>

      {/* Action buttons */}
      <div className="task-card-actions">
        <button className="task-card-action-btn task-card-action-btn--complete" onClick={handleComplete}>
          {task.completedAt ? '↩️ Отменить' : '✅ Выполнить'}
        </button>
        {!editing && (
          <button className="task-card-action-btn task-card-action-btn--edit" onClick={startEditing}>
            ✏️ Редактировать
          </button>
        )}
        {editing && (
          <>
            <button className="task-card-action-btn task-card-action-btn--save" onClick={saveChanges}>
              💾 Сохранить
            </button>
            <button className="task-card-action-btn task-card-action-btn--cancel" onClick={cancelEditing}>
              Отмена
            </button>
          </>
        )}
        <button className="task-card-action-btn task-card-action-btn--delete" onClick={handleDelete}>
          🗑 Удалить
        </button>
      </div>

      {message && <div className="task-card-message">{message}</div>}

      <div className="task-card-body">
        {/* Priority */}
        <div className="task-card-field">
          <span className="task-card-label">Приоритет</span>
          {editing ? (
            <div className="task-card-btn-group">
              {[1, 2, 3].map((p) => (
                <button
                  key={p}
                  className={`priority-btn ${editPriority === p ? 'priority-btn--active' : ''}`}
                  onClick={() => setEditPriority(editPriority === p ? null : (p as Priority))}
                >
                  !{p}
                </button>
              ))}
              {editPriority === null && <span className="task-card-value">—</span>}
            </div>
          ) : (
            <span className={`task-card-value priority-badge priority-${task.priority || 'none'}`}>
              {task.priority ? `!${task.priority} ${priorityLabels[task.priority] || ''}` : '—'}
            </span>
          )}
        </div>

        {/* Due date */}
        <div className="task-card-field">
          <span className="task-card-label">Срок</span>
          {editing ? (
            <input
              type="datetime-local"
              className="task-card-input"
              value={editDueDate}
              onChange={(e) => setEditDueDate(e.target.value)}
            />
          ) : (
            <span className="task-card-value">
              {task.dueDate ? new Date(task.dueDate).toLocaleString() : '—'}
            </span>
          )}
        </div>

        {/* Start date */}
        <div className="task-card-field">
          <span className="task-card-label">Начало</span>
          {editing ? (
            <input
              type="datetime-local"
              className="task-card-input"
              value={editStartDate}
              onChange={(e) => setEditStartDate(e.target.value)}
            />
          ) : (
            <span className="task-card-value">
              {task.startDate ? new Date(task.startDate).toLocaleString() : '—'}
            </span>
          )}
        </div>

        {/* List */}
        <div className="task-card-field">
          <span className="task-card-label">Список</span>
          <span className="task-card-value">{task.listId.replace('sys-', '')}</span>
        </div>

        {/* Tags */}
        <div className="task-card-field">
          <span className="task-card-label">Теги</span>
          {editing ? (
            <input
              type="text"
              className="task-card-input"
              value={editTags}
              onChange={(e) => setEditTags(e.target.value)}
              placeholder="тег1, тег2, ..."
            />
          ) : (
            <div className="task-card-tags">
              {task.tags.length > 0
                ? task.tags.map((t) => (
                    <span key={t} className="task-card-tag">
                      #{t}
                    </span>
                  ))
                : <span className="task-card-value">—</span>}
            </div>
          )}
        </div>

        {/* Repeat */}
        <div className="task-card-field">
          <span className="task-card-label">Повтор</span>
          {editing ? (
            <input
              type="text"
              className="task-card-input"
              value={editRepeatRule}
              onChange={(e) => setEditRepeatRule(e.target.value)}
              placeholder="FREQ=WEEKLY;BYDAY=MO"
            />
          ) : (
            <span className="task-card-value">{task.repeatRule || '—'}</span>
          )}
        </div>

        {/* Estimate */}
        <div className="task-card-field">
          <span className="task-card-label">Оценка</span>
          {editing ? (
            <input
              type="number"
              className="task-card-input"
              value={editEstimate}
              onChange={(e) => setEditEstimate(e.target.value)}
              placeholder="мин"
              min="0"
            />
          ) : (
            <span className="task-card-value">
              {task.estimateMin ? `${task.estimateMin} мин` : '—'}
            </span>
          )}
        </div>

        {/* URL */}
        {editing || task.url ? (
          <div className="task-card-field">
            <span className="task-card-label">URL</span>
            {editing ? (
              <input
                type="url"
                className="task-card-input"
                value={editUrl}
                onChange={(e) => setEditUrl(e.target.value)}
                placeholder="https://..."
              />
            ) : (
              <span className="task-card-value">
                {task.url ? <a href={task.url} target="_blank">{task.url}</a> : '—'}
              </span>
            )}
          </div>
        ) : null}

        {/* Subtasks */}
        <div className="task-card-field">
          <span className="task-card-label">Подзадачи</span>
          <div className="task-card-subtasks">
            {subtasks.length === 0 && <span className="task-card-value">Нет подзадач</span>}
            {subtasks.map((st) => (
              <div key={st.id} className={`task-card-subtask ${st.completedAt ? 'task-card-subtask--done' : ''}`}>
                <input
                  type="checkbox"
                  checked={!!st.completedAt}
                  onChange={() => handleCompleteSubtask(st.id)}
                />
                <span className={`task-card-subtask-name ${st.completedAt ? 'task-card-subtask--done-text' : ''}`}>
                  {st.name}
                </span>
              </div>
            ))}
            <div className="task-card-add-subtask">
              <input
                type="text"
                className="task-card-input task-card-subtask-input"
                value={newSubtaskName}
                onChange={(e) => setNewSubtaskName(e.target.value)}
                placeholder="Добавить подзадачу..."
                onKeyDown={(e) => { if (e.key === 'Enter') handleAddSubtask(); }}
              />
              <button className="task-card-small-btn" onClick={handleAddSubtask}>+</button>
            </div>
          </div>
        </div>

        {/* Notes (Markdown) */}
        <div className="task-card-field">
          <span className="task-card-label">Заметки</span>
          <div className="task-card-notes">
            {editing ? (
              <>
                <div className="task-card-notes-toolbar">
                  <button className="task-card-small-btn" onClick={() => setShowNotesPreview(!showNotesPreview)}>
                    {showNotesPreview ? '✏️ Правка' : '👁 Предпросмотр'}
                  </button>
                </div>
                {showNotesPreview ? (
                  <div className="task-card-notes-preview" dangerouslySetInnerHTML={{ __html: renderMarkdown(editNotes) }}>
                  </div>
                ) : (
                  <textarea
                    className="task-card-notes-textarea"
                    value={editNotes}
                    onChange={(e) => setEditNotes(e.target.value)}
                    placeholder="Markdown заметки..."
                    rows={4}
                  />
                )}
              </>
            ) : (
              <div className="task-card-notes-display">
                {task.notes ? (
                  <div dangerouslySetInnerHTML={{ __html: renderMarkdown(task.notes) }} />
                ) : (
                  <span className="task-card-value">Нет заметок</span>
                )}
              </div>
            )}
          </div>
        </div>

        {/* Attachments placeholder */}
        <div className="task-card-field">
          <span className="task-card-label">Вложения</span>
          <div className="task-card-attachments-stub">
            <span className="task-card-value">
              {task.notes && task.notes.includes('[file:') ? (
                <span>📎 {task.notes.match(/\[file:(.+?)\]/)?.[1] || 'Вложен файл'}</span>
              ) : (
                <span>Вложения будут доступны в v1.1</span>
              )}
            </span>
          </div>
        </div>

        {/* History */}
        <div className="task-card-field">
          <span className="task-card-label">История</span>
          <div className="task-card-history">
            {[
              { label: 'Создано', ts: task.createdAt },
              { label: 'Обновлено', ts: task.updatedAt },
              { label: 'Завершено', ts: task.completedAt },
            ].filter((e) => e.ts).map((e) => (
              <div key={e.label} className="task-card-history-item">
                {e.label}: {new Date(e.ts!).toLocaleString()}
              </div>
            ))}
            <div className="task-card-history-item">Версия: {task.version}</div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default TaskCard;