// Taskly Web — centre pane: list header and menu, open/completed tabs, Smart Add,
// sort bar (or the mass-actions bar while tasks are selected) and the task rows (SPEC 4.1).
import React, { useCallback, useMemo, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import TaskRow from './TaskRow';
import SmartAddInput from '../smartadd/SmartAddInput';
import MassOperationsBar from '../massops/MassOperationsBar';
import { computeView, viewTitle } from '../app/view';
import { completeTargets, createFromSmartAdd, postponeTargets } from './actions';
import { deleteList, updateList } from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import { checkQuery } from '../query/smartQuery';
import type { SortField } from '../../types';

const SORTS: { field: SortField; label: string }[] = [
  { field: 'createdAt', label: 'Добавлены' },
  { field: 'priority', label: 'Приоритет' },
  { field: 'dueDate', label: 'Срок' },
  { field: 'name', label: 'Название' },
];

const TaskListView: React.FC = () => {
  const view = useStore(store, (s) => s.currentView);
  const tab = useStore(store, (s) => s.tab);
  const tasks = useStore(store, (s) => s.tasks);
  const allTasks = useStore(store, (s) => s.allTasks);
  const lists = useStore(store, (s) => s.lists);
  const locations = useStore(store, (s) => s.locations);
  const selected = useStore(store, (s) => s.selectedTaskIds);
  const cursorId = useStore(store, (s) => s.cursorId);
  const openTaskId = useStore(store, (s) => s.openTaskId);
  const isOnline = useStore(store, (s) => s.isOnline);
  const sortBy = useStore(store, (s) => s.sortBy);
  const sortAsc = useStore(store, (s) => s.sortAsc);
  const noteCounts = useStore(store, (s) => s.noteCounts);
  const attachmentCounts = useStore(store, (s) => s.attachmentCounts);

  const title = viewTitle(view, lists, locations);
  const list = view.kind === 'list' || view.kind === 'smart' ? lists.find((l) => l.id === view.listId) : undefined;
  const error = useMemo(
    () => computeView({ view, tab, allTasks, lists, locations, sortBy, sortAsc }).error,
    [view, tab, allTasks, lists, locations, sortBy, sortAsc],
  );

  const subtaskStats = useMemo(() => {
    const m = new Map<string, { done: number; total: number }>();
    for (const t of allTasks) {
      if (!t.parentId) continue;
      const s = m.get(t.parentId) ?? { done: 0, total: 0 };
      s.total++;
      if (t.completedAt) s.done++;
      m.set(t.parentId, s);
    }
    return m;
  }, [allTasks]);
  const locationNames = useMemo(() => new Map(locations.map((l) => [l.id, l.name])), [locations]);

  const onRowClick = useCallback((id: string, e: React.MouseEvent) => {
    const s = store.getState();
    if (e.ctrlKey || e.metaKey) {
      s.toggleTaskSelection(id);
      s.setCursor(id);
      return;
    }
    if (e.shiftKey && s.cursorId) {
      const ids = s.tasks.map((t) => t.id);
      const a = ids.indexOf(s.cursorId);
      const b = ids.indexOf(id);
      if (a >= 0 && b >= 0) {
        const range = ids.slice(Math.min(a, b), Math.max(a, b) + 1);
        s.setSelectedTaskIds([...new Set([...s.selectedTaskIds, ...range])]);
        return;
      }
    }
    s.clearSelection();
    s.setCursor(id);
    s.openTask(id);
  }, []);

  const onToggle = useCallback((id: string) => void completeTargets([id]), []);
  const onPostpone = useCallback((id: string) => void postponeTargets([id]), []);

  const isCompletedView = view.kind === 'system' && view.view === 'completed';
  const canAdd = tab === 'open' && !isCompletedView && view.kind !== 'search';
  const emptyText = isCompletedView || tab === 'completed'
    ? 'Здесь появятся выполненные задачи'
    : view.kind === 'system' && view.view === 'inbox'
      ? 'Всё разобрано'
      : view.kind === 'search' || view.kind === 'smart'
        ? 'Нет подходящих задач'
        : 'Задач нет';

  return (
    <section className="task-list" aria-label={title}>
      <header className="task-list-header">
        <h2 className="task-list-title">{title}</h2>
        <span className="task-list-count" aria-label={`задач: ${tasks.length}`}>{tasks.length}</span>
        {list && !list.isSystem && <ListMenu listId={list.id} name={list.name} smartQuery={list.isSmart ? list.filterQuery ?? '' : null} />}
      </header>

      {!isCompletedView && (
        <div className="task-list-tabs" role="tablist" aria-label="Состояние задач">
          {(['open', 'completed'] as const).map((t) => (
            <button
              key={t}
              role="tab"
              aria-selected={tab === t}
              className={`task-list-tab${tab === t ? ' task-list-tab--active' : ''}`}
              onClick={() => store.getState().setTab(t)}
            >
              {t === 'open' ? 'Невыполненные' : 'Выполненные'}
            </button>
          ))}
        </div>
      )}

      {canAdd && (
        <SmartAddInput
          primary
          onSubmit={(r) => createFromSmartAdd(r)}
          placeholder={isOnline ? undefined : 'Новая задача (офлайн — сохранится и отправится позже)'}
        />
      )}

      {!isOnline && (
        <div className="offline-banner" role="status">
          Нет подключения. Изменения сохранятся на устройстве и отправятся при подключении.
        </div>
      )}

      {selected.length > 0 ? (
        <MassOperationsBar />
      ) : (
        <div className="task-list-sortbar" aria-label="Сортировка">
          <span className="task-list-sortbar-label">Сортировка:</span>
          {SORTS.map((s) => (
            <button
              key={s.field}
              className={`task-list-sort${sortBy === s.field ? ' task-list-sort--active' : ''}`}
              aria-pressed={sortBy === s.field}
              onClick={() => (sortBy === s.field ? store.getState().toggleSortAsc() : store.getState().setSortBy(s.field))}
            >
              {s.label}
              {sortBy === s.field ? (sortAsc ? ' ↑' : ' ↓') : ''}
            </button>
          ))}
        </div>
      )}

      {error ? (
        <div className="task-list-error" role="alert">
          Ошибка в запросе: {error}
        </div>
      ) : tasks.length === 0 ? (
        <div className="task-list-empty">
          <div className="task-list-empty-icon" aria-hidden="true">✓</div>
          <p>{emptyText}</p>
          {canAdd && <p className="task-list-empty-hint">Нажмите T, чтобы добавить задачу. ? — все горячие клавиши.</p>}
        </div>
      ) : (
        <div className="task-list-rows" role="list" aria-label={`Задачи: ${title}`}>
          {tasks.map((task) => (
            <TaskRow
              key={task.id}
              task={task}
              isSelected={selected.includes(task.id)}
              isCursor={cursorId === task.id}
              isOpen={openTaskId === task.id}
              subtasks={subtaskStats.get(task.id)}
              hasNotes={!!noteCounts[task.id]}
              hasAttachments={!!attachmentCounts[task.id]}
              locationName={task.locationId ? locationNames.get(task.locationId) : undefined}
              onClick={onRowClick}
              onToggleComplete={onToggle}
              onPostpone={onPostpone}
            />
          ))}
        </div>
      )}
    </section>
  );
};

/** List menu: rename, edit the smart-list query, delete (tasks of a deleted list go to Inbox). */
const ListMenu: React.FC<{ listId: string; name: string; smartQuery: string | null }> = ({ listId, name, smartQuery }) => {
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<'name' | 'query' | null>(null);
  const [value, setValue] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  async function save() {
    if (editing === 'name' && value.trim()) await updateList(listId, { name: value.trim() });
    if (editing === 'query') {
      const p = checkQuery(value);
      if (p) {
        setProblem(p.message);
        return;
      }
      await updateList(listId, { filterQuery: value });
    }
    setEditing(null);
    setProblem(null);
    await refreshData();
  }

  if (editing) {
    return (
      <form
        className="list-menu-edit"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <input
          className="list-menu-input"
          aria-label={editing === 'name' ? 'Название списка' : 'Запрос умного списка'}
          value={value}
          autoFocus
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => e.key === 'Escape' && setEditing(null)}
        />
        <button type="submit" className="list-menu-btn">Сохранить</button>
        {problem && <span className="list-menu-error" role="alert">{problem}</span>}
      </form>
    );
  }

  return (
    <div className="list-menu">
      <button className="list-menu-toggle" aria-haspopup="menu" aria-expanded={open} aria-label="Меню списка" onClick={() => setOpen(!open)}>
        ⋯
      </button>
      {open && (
        <div className="list-menu-items" role="menu">
          <button role="menuitem" onClick={() => { setOpen(false); setValue(name); setEditing('name'); }}>Переименовать</button>
          {smartQuery !== null && (
            <button role="menuitem" onClick={() => { setOpen(false); setValue(smartQuery); setEditing('query'); }}>Изменить запрос</button>
          )}
          <button
            role="menuitem"
            className="list-menu-danger"
            onClick={async () => {
              setOpen(false);
              if (!window.confirm(`Удалить список «${name}»?${smartQuery === null ? ' Задачи переместятся во «Входящие».' : ''}`)) return;
              await deleteList(listId);
              store.getState().setCurrentView({ kind: 'system', view: 'inbox' });
              await refreshData();
            }}
          >
            Удалить список
          </button>
        </div>
      )}
    </div>
  );
};

export default TaskListView;
