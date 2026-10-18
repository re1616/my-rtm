// Taskly Web — task card (WEB-F3, SPEC 4.1): every property as an editable "key — value" row,
// subtasks with progress, markdown notes, link attachments and the change history.
import React, { useEffect, useMemo, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import type { Task, TaskAttachment, TaskHistoryEntry, TaskNote } from '../../types';
import {
  INBOX_ID,
  addAttachment,
  addNote,
  deleteNote,
  getAttachments,
  getNotes,
  isSafeLink,
  removeAttachment,
  updateNote,
  updateTask,
} from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import {
  addTagsTargets,
  completeTargets,
  createFromSmartAdd,
  deleteTargets,
  parseDue,
  parseRepeat,
  postponeTargets,
  removeTagsTargets,
  renameTask,
  setPriorityTargets,
} from './actions';
import { formatDue } from './TaskRow';
import Markdown from './Markdown';
import SmartAddInput from '../smartadd/SmartAddInput';
import { fetchTaskHistory } from '../sync/SyncService';

const MAX_DEPTH = 3;

function depthOf(task: Task, all: Map<string, Task>): number {
  let d = 1;
  let p = task.parentId ? all.get(task.parentId) : undefined;
  while (p && d < 10) {
    d++;
    p = p.parentId ? all.get(p.parentId) : undefined;
  }
  return d;
}

/** Click-to-edit value; Enter saves, Esc cancels. */
const EditableText: React.FC<{
  label: string;
  display: React.ReactNode;
  initial: string;
  placeholder?: string;
  onSave: (v: string) => Promise<boolean | void>;
  hint?: (v: string) => string | null;
}> = ({ label, display, initial, placeholder, onSave, hint }) => {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(initial);
  const [error, setError] = useState(false);
  useEffect(() => setValue(initial), [initial]);
  if (!editing) {
    return (
      <button type="button" className="card-value card-value--button" aria-label={`${label}: изменить`} onClick={() => setEditing(true)}>
        {display}
      </button>
    );
  }
  const h = hint?.(value);
  return (
    <form
      className="card-edit"
      onSubmit={async (e) => {
        e.preventDefault();
        const ok = await onSave(value);
        if (ok === false) setError(true);
        else setEditing(false);
      }}
    >
      <input
        className="card-input"
        aria-label={label}
        autoFocus
        value={value}
        placeholder={placeholder}
        onChange={(e) => {
          setValue(e.target.value);
          setError(false);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.preventDefault();
            setValue(initial);
            setEditing(false);
          }
        }}
      />
      {h && <span className="card-hint">→ {h}</span>}
      {error && <span className="card-error" role="alert">Не распознано</span>}
    </form>
  );
};

const TaskCard: React.FC = () => {
  const openTaskId = useStore(store, (s) => s.openTaskId);
  const allTasks = useStore(store, (s) => s.allTasks);
  const lists = useStore(store, (s) => s.lists);
  const locations = useStore(store, (s) => s.locations);
  const session = useStore(store, (s) => s.session);
  const byId = useMemo(() => new Map(allTasks.map((t) => [t.id, t])), [allTasks]);
  const task = openTaskId ? byId.get(openTaskId) : undefined;

  const [notes, setNotes] = useState<TaskNote[]>([]);
  const [attachments, setAttachments] = useState<TaskAttachment[]>([]);
  const [history, setHistory] = useState<TaskHistoryEntry[] | null | 'error'>(null);

  // Notes/attachments: reloaded with every data refresh (other tabs, the server)
  useEffect(() => {
    if (!openTaskId) return;
    let cancelled = false;
    void Promise.all([getNotes(openTaskId), getAttachments(openTaskId)]).then(([n, a]) => {
      if (!cancelled) {
        setNotes(n);
        setAttachments(a);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [openTaskId, allTasks]);

  useEffect(() => {
    setHistory(null);
  }, [openTaskId]);

  if (!task) return null;
  const t = task;
  const subtasks = allTasks.filter((x) => x.parentId === t.id).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const doneSubtasks = subtasks.filter((x) => x.completedAt).length;
  const parent = t.parentId ? byId.get(t.parentId) : undefined;
  const canAddSubtask = depthOf(t, byId) < MAX_DEPTH;
  const due = formatDue(t);
  const userLists = lists.filter((l) => !l.isSystem && !l.isSmart);

  const close = () => store.getState().openTask(null);
  const reloadChildren = async () => {
    setNotes(await getNotes(t.id));
    setAttachments(await getAttachments(t.id));
    await refreshData();
  };
  const save = async (changes: Partial<Task>) => {
    await updateTask(t.id, changes);
    await refreshData();
  };

  return (
    <aside className="task-card" aria-label={`Задача: ${t.name}`}>
      <div className="task-card-header">
        <button
          type="button"
          className={`task-row-check${t.completedAt ? ' task-row-check--done' : ''}`}
          aria-label={t.completedAt ? 'Вернуть в работу' : 'Выполнить'}
          aria-pressed={!!t.completedAt}
          onClick={() => void completeTargets([t.id])}
        >
          {t.completedAt ? '✓' : ''}
        </button>
        <div className="task-card-title-wrap">
          {parent && (
            <button type="button" className="task-card-parent" onClick={() => store.getState().openTask(parent.id)}>
              ↑ {parent.name}
            </button>
          )}
          <EditableText
            label="Название"
            display={<h3 className="task-card-title">{t.name}</h3>}
            initial={t.name}
            onSave={async (v) => {
              await renameTask(t.id, v);
            }}
          />
        </div>
        <button type="button" className="task-card-close" onClick={close} aria-label="Закрыть карточку">
          ×
        </button>
      </div>

      <div className="task-card-actions">
        {!t.completedAt && (
          <button type="button" className="card-btn" onClick={() => void postponeTargets([t.id])}>
            Отложить
          </button>
        )}
        <button type="button" className="card-btn card-btn--danger" onClick={() => void deleteTargets([t.id])}>
          Удалить
        </button>
      </div>

      <dl className="task-card-props">
        <dt>Список</dt>
        <dd>
          <select
            className="card-select"
            aria-label="Список"
            value={t.listId && t.listId !== INBOX_ID && userLists.some((l) => l.id === t.listId) ? t.listId : INBOX_ID}
            onChange={(e) => void save({ listId: e.target.value })}
          >
            <option value={INBOX_ID}>Входящие</option>
            {userLists.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </select>
        </dd>

        <dt>Приоритет</dt>
        <dd className="card-priority" role="group" aria-label="Приоритет">
          {([1, 2, 3, null] as const).map((p) => (
            <button
              key={String(p)}
              type="button"
              className={`card-priority-btn card-priority-btn--${p ?? 'none'}${t.priority === p ? ' card-priority-btn--active' : ''}`}
              aria-pressed={t.priority === p}
              onClick={() => void setPriorityTargets(p, [t.id])}
            >
              {p ? `!${p}` : 'нет'}
            </button>
          ))}
        </dd>

        <dt>Срок</dt>
        <dd>
          <EditableText
            label="Срок"
            display={due.text ? <span className={`task-row-due--${due.tone}`}>{due.text}</span> : <span className="card-empty">не задан</span>}
            initial=""
            placeholder="завтра 17:00, пт, 25 дек…"
            hint={(v) => (v.trim() ? (parseDue(v)?.dueDate ? new Date(parseDue(v)!.dueDate!).toLocaleString() : null) : 'без срока')}
            onSave={async (v) => {
              const d = parseDue(v);
              if (!d) return false;
              await save(d);
            }}
          />
        </dd>

        <dt>Начало</dt>
        <dd>
          <EditableText
            label="Дата начала"
            display={t.startDate ? new Date(t.startDate).toLocaleDateString() : <span className="card-empty">не задана</span>}
            initial=""
            placeholder="понедельник"
            onSave={async (v) => {
              const d = parseDue(v);
              if (!d) return false;
              await save({ startDate: d.dueDate });
            }}
          />
        </dd>

        <dt>Повтор</dt>
        <dd>
          <EditableText
            label="Повтор"
            display={t.repeatRule ? `${t.repeatRule}${t.repeatMode === 'after' ? ' (после выполнения)' : ''}` : <span className="card-empty">нет</span>}
            initial=""
            placeholder="каждую неделю, after 2 weeks"
            hint={(v) => (v.trim() ? parseRepeat(v)?.repeatRule ?? null : 'без повтора')}
            onSave={async (v) => {
              const r = parseRepeat(v);
              if (!r) return false;
              await save(r);
            }}
          />
        </dd>

        <dt>Оценка</dt>
        <dd>
          <EditableText
            label="Оценка, минут"
            display={t.estimateMin ? `${t.estimateMin} мин` : <span className="card-empty">нет</span>}
            initial={t.estimateMin ? String(t.estimateMin) : ''}
            placeholder="минуты"
            onSave={async (v) => {
              if (!v.trim()) return void (await save({ estimateMin: undefined }));
              const n = Number(v);
              if (!Number.isInteger(n) || n < 0) return false;
              await save({ estimateMin: n });
            }}
          />
        </dd>

        <dt>Теги</dt>
        <dd className="card-tags">
          {t.tags.map((tag) => (
            <span key={tag} className="card-tag">
              #{tag}
              <button type="button" className="card-tag-remove" aria-label={`Убрать тег ${tag}`} onClick={() => void removeTagsTargets([tag], [t.id])}>
                ×
              </button>
            </span>
          ))}
          <EditableText
            label="Добавить тег"
            display={<span className="card-empty">+ тег</span>}
            initial=""
            onSave={async (v) => {
              await addTagsTargets(v.split(/[\s,]+/), [t.id]);
            }}
          />
        </dd>

        <dt>Место</dt>
        <dd>
          <select
            className="card-select"
            aria-label="Место"
            value={t.locationId ?? ''}
            onChange={(e) => void save({ locationId: e.target.value || undefined })}
          >
            <option value="">—</option>
            {locations.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </select>
        </dd>

        <dt>Ссылка</dt>
        <dd>
          <EditableText
            label="Ссылка"
            display={
              t.url && isSafeLink(t.url) ? (
                <span className="card-link">{t.url}</span>
              ) : (
                <span className="card-empty">нет</span>
              )
            }
            initial={t.url ?? ''}
            placeholder="https://…"
            onSave={async (v) => {
              if (v.trim() && !isSafeLink(v.trim())) return false;
              await save({ url: v.trim() || undefined });
            }}
          />
          {t.url && isSafeLink(t.url) && (
            <a className="card-open-link" href={t.url} target="_blank" rel="noopener noreferrer nofollow" aria-label="Открыть ссылку">
              ↗
            </a>
          )}
        </dd>

        {(t.postponedCount ?? 0) > 0 && (
          <>
            <dt>Отложена</dt>
            <dd>{t.postponedCount} раз</dd>
          </>
        )}
      </dl>

      <section className="task-card-section" aria-label="Подзадачи">
        <h4>
          Подзадачи {subtasks.length > 0 && <span className="card-progress">{doneSubtasks} из {subtasks.length}</span>}
        </h4>
        <ul className="card-subtasks">
          {subtasks.map((s) => (
            <li key={s.id} className={s.completedAt ? 'card-subtask card-subtask--done' : 'card-subtask'}>
              <button
                type="button"
                className={`task-row-check${s.completedAt ? ' task-row-check--done' : ''}`}
                aria-label={s.completedAt ? `Вернуть «${s.name}» в работу` : `Выполнить «${s.name}»`}
                aria-pressed={!!s.completedAt}
                onClick={() => void completeTargets([s.id])}
              >
                {s.completedAt ? '✓' : ''}
              </button>
              <button type="button" className="card-subtask-name" onClick={() => store.getState().openTask(s.id)}>
                {s.name}
              </button>
            </li>
          ))}
        </ul>
        {canAddSubtask ? (
          <SmartAddInput label="Новая подзадача" placeholder="Подзадача: ^срок !приоритет…" onSubmit={(r) => createFromSmartAdd(r, { parentId: t.id })} />
        ) : (
          <p className="card-empty">Вложенность — не больше трёх уровней.</p>
        )}
      </section>

      <NotesSection taskId={t.id} notes={notes} onChange={reloadChildren} />

      <AttachmentsSection taskId={t.id} attachments={attachments} onChange={reloadChildren} />

      <section className="task-card-section" aria-label="История">
        <h4>История</h4>
        <ul className="card-history">
          <li>Создана: {new Date(t.createdAt).toLocaleString()}</li>
          {t.completedAt && <li>Выполнена: {new Date(t.completedAt).toLocaleString()}</li>}
          <li>Изменена: {new Date(t.updatedAt).toLocaleString()}</li>
        </ul>
        {session &&
          (history === null ? (
            <button
              type="button"
              className="card-btn card-btn--ghost"
              onClick={() =>
                void fetchTaskHistory(t.id).then(
                  (h) => setHistory(h ?? []),
                  () => setHistory('error'),
                )
              }
            >
              Показать все изменения
            </button>
          ) : history === 'error' ? (
            <p className="card-empty">История недоступна офлайн.</p>
          ) : (
            <ul className="card-history card-history--server">
              {history.map((h) => (
                <li key={h.seq}>
                  {new Date(h.ts).toLocaleString()} — {describeChange(h)}
                </li>
              ))}
              {history.length === 0 && <li className="card-empty">На сервере изменений ещё нет.</li>}
            </ul>
          ))}
      </section>
    </aside>
  );
};

const OP_TEXT: Record<string, string> = {
  create: 'создана',
  update: 'изменена',
  move: 'перемещена',
  complete: 'выполнена',
  uncomplete: 'возвращена в работу',
  delete: 'удалена',
  restore: 'восстановлена',
  tag_add: 'добавлен тег',
  tag_remove: 'убран тег',
};

const FIELD_TEXT: Record<string, string> = {
  name: 'название',
  list_id: 'список',
  priority: 'приоритет',
  due_at: 'срок',
  start_at: 'начало',
  estimate_min: 'оценка',
  rrule: 'повтор',
  url: 'ссылка',
  location_id: 'место',
  parent_id: 'родитель',
  postponed_count: 'отложена',
};

export function describeChange(h: TaskHistoryEntry): string {
  let p: Record<string, unknown> = {};
  try {
    p = JSON.parse(h.payload) as Record<string, unknown>;
  } catch {
    /* empty */
  }
  if (h.entity === 'note') return h.op === 'delete' ? 'заметка удалена' : h.op === 'create' ? 'добавлена заметка' : 'заметка изменена';
  if (h.entity === 'attachment') return h.op === 'delete' ? 'вложение удалено' : `добавлено вложение ${String(p.name ?? '')}`;
  if (h.op === 'tag_add' || h.op === 'tag_remove') return `${OP_TEXT[h.op]} #${String(p.tag ?? '')}`;
  if (h.op === 'update' || h.op === 'move') {
    const fields = Object.keys(p).map((k) => FIELD_TEXT[k] ?? k);
    return fields.length ? `изменено: ${fields.join(', ')}` : 'изменена';
  }
  return OP_TEXT[h.op] ?? h.op;
}

const NotesSection: React.FC<{ taskId: string; notes: TaskNote[]; onChange: () => Promise<void> }> = ({ taskId, notes, onChange }) => {
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  return (
    <section className="task-card-section" aria-label="Заметки">
      <h4>Заметки</h4>
      {notes.map((n) =>
        editing === n.id ? (
          <form
            key={n.id}
            className="card-note-edit"
            onSubmit={async (e) => {
              e.preventDefault();
              await updateNote(n.id, editText);
              setEditing(null);
              await onChange();
            }}
          >
            <textarea className="card-textarea" aria-label="Текст заметки" value={editText} onChange={(e) => setEditText(e.target.value)} autoFocus />
            <div className="card-note-actions">
              <button type="submit" className="card-btn">Сохранить</button>
              <button type="button" className="card-btn card-btn--ghost" onClick={() => setEditing(null)}>Отмена</button>
            </div>
          </form>
        ) : (
          <article key={n.id} className="card-note">
            <Markdown text={n.content} className="markdown card-note-body" />
            <div className="card-note-actions">
              <button type="button" className="card-btn card-btn--ghost" onClick={() => { setEditing(n.id); setEditText(n.content); }}>
                Изменить
              </button>
              <button
                type="button"
                className="card-btn card-btn--ghost"
                onClick={async () => {
                  await deleteNote(n.id);
                  await onChange();
                }}
              >
                Удалить
              </button>
            </div>
          </article>
        ),
      )}
      <form
        className="card-note-new"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!draft.trim()) return;
          await addNote(taskId, draft.trim());
          setDraft('');
          await onChange();
        }}
      >
        <textarea
          className="card-textarea"
          aria-label="Новая заметка"
          placeholder="Заметка (markdown: **жирный**, *курсив*, - списки, [ссылки](https://…))"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        {draft.trim() && <Markdown text={draft} className="markdown card-note-preview" />}
        <button type="submit" className="card-btn" disabled={!draft.trim()}>
          Добавить заметку
        </button>
      </form>
    </section>
  );
};

const AttachmentsSection: React.FC<{ taskId: string; attachments: TaskAttachment[]; onChange: () => Promise<void> }> = ({ taskId, attachments, onChange }) => {
  const [url, setUrl] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  return (
    <section className="task-card-section" aria-label="Вложения">
      <h4>Вложения</h4>
      <ul className="card-attachments">
        {attachments.map((a) => (
          <li key={a.id}>
            {isSafeLink(a.url) ? (
              <a href={a.url} target="_blank" rel="noopener noreferrer nofollow">
                📎 {a.name}
              </a>
            ) : (
              <span>📎 {a.name}</span>
            )}
            <button
              type="button"
              className="card-tag-remove"
              aria-label={`Удалить вложение ${a.name}`}
              onClick={async () => {
                await removeAttachment(a.id);
                await onChange();
              }}
            >
              ×
            </button>
          </li>
        ))}
      </ul>
      <form
        className="card-attach-new"
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            await addAttachment(taskId, url, name);
            setUrl('');
            setName('');
            setError(null);
            await onChange();
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Не удалось добавить');
          }
        }}
      >
        <input className="card-input" aria-label="Ссылка на файл" placeholder="https://drive…" value={url} onChange={(e) => setUrl(e.target.value)} />
        <input className="card-input" aria-label="Название вложения" placeholder="Название" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit" className="card-btn" disabled={!url.trim()}>
          Прикрепить ссылку
        </button>
        {error && <span className="card-error" role="alert">{error}</span>}
      </form>
    </section>
  );
};

export default TaskCard;
