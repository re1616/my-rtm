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
import { formatDate, formatDateTime, msg, t } from '../../i18n/i18n';

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
      <button type="button" className="card-value card-value--button" aria-label={t('{label}: изменить', { label })} onClick={() => setEditing(true)}>
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
      {error && <span className="card-error" role="alert">{t('Не распознано')}</span>}
    </form>
  );
};

const TaskCard: React.FC = () => {
  const openTaskId = useStore(store, (s) => s.openTaskId);
  const allTasks = useStore(store, (s) => s.allTasks);
  const lists = useStore(store, (s) => s.lists);
  const locations = useStore(store, (s) => s.locations);
  const session = useStore(store, (s) => s.session);
  const byId = useMemo(() => new Map(allTasks.map((x) => [x.id, x])), [allTasks]);
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
  const tk = task;
  const subtasks = allTasks.filter((x) => x.parentId === tk.id).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const doneSubtasks = subtasks.filter((x) => x.completedAt).length;
  const parent = tk.parentId ? byId.get(tk.parentId) : undefined;
  const canAddSubtask = depthOf(tk, byId) < MAX_DEPTH;
  const due = formatDue(tk);
  const userLists = lists.filter((l) => !l.isSystem && !l.isSmart);

  const close = () => store.getState().openTask(null);
  const reloadChildren = async () => {
    setNotes(await getNotes(tk.id));
    setAttachments(await getAttachments(tk.id));
    await refreshData();
  };
  const save = async (changes: Partial<Task>) => {
    await updateTask(tk.id, changes);
    await refreshData();
  };

  return (
    <aside className="task-card" aria-label={t('Задача: {name}', { name: tk.name })}>
      <div className="task-card-header">
        <button
          type="button"
          className={`task-row-check${tk.completedAt ? ' task-row-check--done' : ''}`}
          aria-label={tk.completedAt ? t('Вернуть в работу') : t('Выполнить')}
          aria-pressed={!!tk.completedAt}
          onClick={() => void completeTargets([tk.id])}
        >
          {tk.completedAt ? '✓' : ''}
        </button>
        <div className="task-card-title-wrap">
          {parent && (
            <button type="button" className="task-card-parent" onClick={() => store.getState().openTask(parent.id)}>
              ↑ {parent.name}
            </button>
          )}
          <EditableText
            label={t('Название')}
            display={<h3 className="task-card-title">{tk.name}</h3>}
            initial={tk.name}
            onSave={async (v) => {
              await renameTask(tk.id, v);
            }}
          />
        </div>
        <button type="button" className="task-card-close" onClick={close} aria-label={t('Закрыть карточку')}>
          ×
        </button>
      </div>

      <div className="task-card-actions">
        {!tk.completedAt && (
          <button type="button" className="card-btn" onClick={() => void postponeTargets([tk.id])}>
            {t('Отложить')}
          </button>
        )}
        <button type="button" className="card-btn card-btn--danger" onClick={() => void deleteTargets([tk.id])}>
          {t('Удалить')}
        </button>
      </div>

      <dl className="task-card-props">
        <dt>{t('Список')}</dt>
        <dd>
          <select
            className="card-select"
            aria-label={t('Список')}
            value={tk.listId && tk.listId !== INBOX_ID && userLists.some((l) => l.id === tk.listId) ? tk.listId : INBOX_ID}
            onChange={(e) => void save({ listId: e.target.value })}
          >
            <option value={INBOX_ID}>{t('Входящие')}</option>
            {userLists.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </select>
        </dd>

        <dt>{t('Приоритет')}</dt>
        <dd>
          <span className="card-priority" role="group" aria-label={t('Приоритет')}>
          {([1, 2, 3, null] as const).map((p) => (
            <button
              key={String(p)}
              type="button"
              className={`card-priority-btn card-priority-btn--${p ?? 'none'}${tk.priority === p ? ' card-priority-btn--active' : ''}`}
              aria-pressed={tk.priority === p}
              onClick={() => void setPriorityTargets(p, [tk.id])}
            >
              {p ? `!${p}` : t('нет')}
            </button>
          ))}
          </span>
        </dd>

        <dt>{t('Срок')}</dt>
        <dd>
          <EditableText
            label={t('Срок')}
            display={due.text ? <span className={`task-row-due--${due.tone}`}>{due.text}</span> : <span className="card-empty">{t('не задан')}</span>}
            initial=""
            placeholder={t('завтра 17:00, пт, 25 дек…')}
            hint={(v) => (v.trim() ? (parseDue(v)?.dueDate ? formatDateTime(new Date(parseDue(v)!.dueDate!)) : null) : t('без срока'))}
            onSave={async (v) => {
              const d = parseDue(v);
              if (!d) return false;
              await save(d);
            }}
          />
        </dd>

        <dt>{t('Начало')}</dt>
        <dd>
          <EditableText
            label={t('Дата начала')}
            display={tk.startDate ? formatDate(new Date(tk.startDate)) : <span className="card-empty">{t('не задана')}</span>}
            initial=""
            placeholder={t('понедельник')}
            onSave={async (v) => {
              const d = parseDue(v);
              if (!d) return false;
              await save({ startDate: d.dueDate });
            }}
          />
        </dd>

        <dt>{t('Повтор')}</dt>
        <dd>
          <EditableText
            label={t('Повтор')}
            display={tk.repeatRule ? `${tk.repeatRule}${tk.repeatMode === 'after' ? ' ' + t('(после выполнения)') : ''}` : <span className="card-empty">{t('нет')}</span>}
            initial=""
            placeholder={t('каждую неделю, after 2 weeks')}
            hint={(v) => (v.trim() ? parseRepeat(v)?.repeatRule ?? null : t('без повтора'))}
            onSave={async (v) => {
              const r = parseRepeat(v);
              if (!r) return false;
              await save(r);
            }}
          />
        </dd>

        <dt>{t('Оценка')}</dt>
        <dd>
          <EditableText
            label={t('Оценка, минут')}
            display={tk.estimateMin ? t('{n} мин', { n: tk.estimateMin }) : <span className="card-empty">{t('нет')}</span>}
            initial={tk.estimateMin ? String(tk.estimateMin) : ''}
            placeholder={t('минуты')}
            onSave={async (v) => {
              if (!v.trim()) return void (await save({ estimateMin: undefined }));
              const n = Number(v);
              if (!Number.isInteger(n) || n < 0) return false;
              await save({ estimateMin: n });
            }}
          />
        </dd>

        <dt>{t('Теги')}</dt>
        <dd className="card-tags">
          {tk.tags.map((tag) => (
            <span key={tag} className="card-tag">
              #{tag}
              <button type="button" className="card-tag-remove" aria-label={t('Убрать тег {tag}', { tag })} onClick={() => void removeTagsTargets([tag], [tk.id])}>
                ×
              </button>
            </span>
          ))}
          <EditableText
            label={t('Добавить тег')}
            display={<span className="card-empty">{t('+ тег')}</span>}
            initial=""
            onSave={async (v) => {
              await addTagsTargets(v.split(/[\s,]+/), [tk.id]);
            }}
          />
        </dd>

        <dt>{t('Место')}</dt>
        <dd>
          <select
            className="card-select"
            aria-label={t('Место')}
            value={tk.locationId ?? ''}
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

        <dt>{t('Ссылка')}</dt>
        <dd>
          <EditableText
            label={t('Ссылка')}
            display={
              tk.url && isSafeLink(tk.url) ? (
                <span className="card-link">{tk.url}</span>
              ) : (
                <span className="card-empty">{t('нет')}</span>
              )
            }
            initial={tk.url ?? ''}
            placeholder="https://…"
            onSave={async (v) => {
              if (v.trim() && !isSafeLink(v.trim())) return false;
              await save({ url: v.trim() || undefined });
            }}
          />
          {tk.url && isSafeLink(tk.url) && (
            <a className="card-open-link" href={tk.url} target="_blank" rel="noopener noreferrer nofollow" aria-label={t('Открыть ссылку')}>
              ↗
            </a>
          )}
        </dd>

        {(tk.postponedCount ?? 0) > 0 && (
          <>
            <dt>{t('Отложена')}</dt>
            <dd>{t('{n} раз', { n: tk.postponedCount ?? 0 })}</dd>
          </>
        )}
      </dl>

      <section className="task-card-section" aria-label={t('Подзадачи')}>
        <h4>
          {t('Подзадачи')} {subtasks.length > 0 && <span className="card-progress">{t('{done} из {total}', { done: doneSubtasks, total: subtasks.length })}</span>}
        </h4>
        <ul className="card-subtasks">
          {subtasks.map((s) => (
            <li key={s.id} className={s.completedAt ? 'card-subtask card-subtask--done' : 'card-subtask'}>
              <button
                type="button"
                className={`task-row-check${s.completedAt ? ' task-row-check--done' : ''}`}
                aria-label={s.completedAt ? t('Вернуть «{name}» в работу', { name: s.name }) : t('Выполнить «{name}»', { name: s.name })}
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
          <SmartAddInput label={t('Новая подзадача')} placeholder={t('Подзадача: ^срок !приоритет…')} onSubmit={(r) => createFromSmartAdd(r, { parentId: tk.id })} />
        ) : (
          <p className="card-empty">{t('Вложенность — не больше трёх уровней.')}</p>
        )}
      </section>

      <NotesSection taskId={tk.id} notes={notes} onChange={reloadChildren} />

      <AttachmentsSection taskId={tk.id} attachments={attachments} onChange={reloadChildren} />

      <section className="task-card-section" aria-label={t('История')}>
        <h4>{t('История')}</h4>
        <ul className="card-history">
          <li>{t('Создана: {date}', { date: formatDateTime(new Date(tk.createdAt)) })}</li>
          {tk.completedAt && <li>{t('Выполнена: {date}', { date: formatDateTime(new Date(tk.completedAt)) })}</li>}
          <li>{t('Изменена: {date}', { date: formatDateTime(new Date(tk.updatedAt)) })}</li>
        </ul>
        {session &&
          (history === null ? (
            <button
              type="button"
              className="card-btn card-btn--ghost"
              onClick={() =>
                void fetchTaskHistory(tk.id).then(
                  (h) => setHistory(h ?? []),
                  () => setHistory('error'),
                )
              }
            >
              {t('Показать все изменения')}
            </button>
          ) : history === 'error' ? (
            <p className="card-empty">{t('История недоступна офлайн.')}</p>
          ) : (
            <ul className="card-history card-history--server">
              {history.map((h) => (
                <li key={h.seq}>
                  {formatDateTime(new Date(h.ts))} — {describeChange(h)}
                </li>
              ))}
              {history.length === 0 && <li className="card-empty">{t('На сервере изменений ещё нет.')}</li>}
            </ul>
          ))}
      </section>
    </aside>
  );
};

const OP_TEXT: Record<string, string> = {
  create: msg('создана'),
  update: msg('изменена'),
  move: msg('перемещена'),
  complete: msg('выполнена'),
  uncomplete: msg('возвращена в работу'),
  delete: msg('удалена'),
  restore: msg('восстановлена'),
  tag_add: msg('добавлен тег'),
  tag_remove: msg('убран тег'),
};

const FIELD_TEXT: Record<string, string> = {
  name: msg('название'),
  list_id: msg('список'),
  priority: msg('приоритет'),
  due_at: msg('срок'),
  start_at: msg('начало'),
  estimate_min: msg('оценка'),
  rrule: msg('повтор'),
  url: msg('ссылка'),
  location_id: msg('место'),
  parent_id: msg('родитель'),
  postponed_count: msg('отложена'),
};

export function describeChange(h: TaskHistoryEntry): string {
  let p: Record<string, unknown> = {};
  try {
    p = JSON.parse(h.payload) as Record<string, unknown>;
  } catch {
    /* empty */
  }
  if (h.entity === 'note') return h.op === 'delete' ? t('заметка удалена') : h.op === 'create' ? t('добавлена заметка') : t('заметка изменена');
  if (h.entity === 'attachment') return h.op === 'delete' ? t('вложение удалено') : t('добавлено вложение {name}', { name: String(p.name ?? '') });
  if (h.op === 'tag_add' || h.op === 'tag_remove') return `${t(OP_TEXT[h.op])} #${String(p.tag ?? '')}`;
  if (h.op === 'update' || h.op === 'move') {
    const fields = Object.keys(p).map((k) => (FIELD_TEXT[k] ? t(FIELD_TEXT[k]) : k));
    return fields.length ? t('изменено: {fields}', { fields: fields.join(', ') }) : t('изменена');
  }
  return OP_TEXT[h.op] ? t(OP_TEXT[h.op]) : h.op;
}

const NotesSection: React.FC<{ taskId: string; notes: TaskNote[]; onChange: () => Promise<void> }> = ({ taskId, notes, onChange }) => {
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  return (
    <section className="task-card-section" aria-label={t('Заметки')}>
      <h4>{t('Заметки')}</h4>
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
            <textarea className="card-textarea" aria-label={t('Текст заметки')} value={editText} onChange={(e) => setEditText(e.target.value)} autoFocus />
            <div className="card-note-actions">
              <button type="submit" className="card-btn">{t('Сохранить')}</button>
              <button type="button" className="card-btn card-btn--ghost" onClick={() => setEditing(null)}>{t('Отмена')}</button>
            </div>
          </form>
        ) : (
          <article key={n.id} className="card-note">
            <Markdown text={n.content} className="markdown card-note-body" />
            <div className="card-note-actions">
              <button type="button" className="card-btn card-btn--ghost" onClick={() => { setEditing(n.id); setEditText(n.content); }}>
                {t('Изменить')}
              </button>
              <button
                type="button"
                className="card-btn card-btn--ghost"
                onClick={async () => {
                  await deleteNote(n.id);
                  await onChange();
                }}
              >
                {t('Удалить')}
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
          aria-label={t('Новая заметка')}
          placeholder={t('Заметка (markdown: **жирный**, *курсив*, - списки, [ссылки](https://…))')}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        {draft.trim() && <Markdown text={draft} className="markdown card-note-preview" />}
        <button type="submit" className="card-btn" disabled={!draft.trim()}>
          {t('Добавить заметку')}
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
    <section className="task-card-section" aria-label={t('Вложения')}>
      <h4>{t('Вложения')}</h4>
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
              aria-label={t('Удалить вложение {name}', { name: a.name })}
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
            setError(err instanceof Error ? err.message : t('Не удалось добавить'));
          }
        }}
      >
        <input className="card-input" aria-label={t('Ссылка на файл')} placeholder="https://drive…" value={url} onChange={(e) => setUrl(e.target.value)} />
        <input className="card-input" aria-label={t('Название вложения')} placeholder={t('Название')} value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit" className="card-btn" disabled={!url.trim()}>
          {t('Прикрепить ссылку')}
        </button>
        {error && <span className="card-error" role="alert">{error}</span>}
      </form>
    </section>
  );
};

export default TaskCard;
