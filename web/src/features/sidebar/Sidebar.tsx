// Taskly Web — sidebar (SPEC 4.1): Inbox and system views, lists, smart lists, tags, locations,
// each with task counts; "+" creates a list or a smart list. Collapses to icons.
import React, { useMemo, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import type { AppView, Task } from '../../types';
import AuthPanel from '../sync/AuthPanel';
import { SYSTEM_VIEWS, sameView } from '../app/view';
import { inView, createList } from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import { checkQuery, runQuery } from '../query/smartQuery';

export function navigate(view: AppView): void {
  const s = store.getState();
  s.setCurrentView(view);
  window.dispatchEvent(new CustomEvent('taskly-navigate', { detail: view }));
}

const Item: React.FC<{ view: AppView; current: AppView; icon: string; label: string; count?: number; collapsed: boolean }> = ({
  view,
  current,
  icon,
  label,
  count,
  collapsed,
}) => {
  const active = sameView(view, current);
  return (
    <button
      type="button"
      className={`sidebar-item${active ? ' sidebar-item--active' : ''}`}
      aria-current={active ? 'page' : undefined}
      title={collapsed ? label : undefined}
      onClick={() => navigate(view)}
    >
      <span className="sidebar-item-icon" aria-hidden="true">{icon}</span>
      <span className="sidebar-item-label">{label}</span>
      {count !== undefined && count > 0 && (
        <span className="sidebar-item-count" aria-label={`, задач: ${count}`}>
          {count}
        </span>
      )}
    </button>
  );
};

const Sidebar: React.FC = () => {
  const current = useStore(store, (s) => s.currentView);
  const allTasks = useStore(store, (s) => s.allTasks);
  const lists = useStore(store, (s) => s.lists);
  const tagNames = useStore(store, (s) => s.tagNames);
  const locations = useStore(store, (s) => s.locations);
  const isOnline = useStore(store, (s) => s.isOnline);
  const [collapsed, setCollapsed] = useState(false);
  const [newList, setNewList] = useState<string | null>(null);

  const open = useMemo(() => allTasks.filter((t) => !t.completedAt), [allTasks]);
  const userLists = lists.filter((l) => !l.isSystem && !l.isSmart);
  const smartLists = lists.filter((l) => l.isSmart);

  const counts = useMemo(() => {
    const top = (tasks: Task[]) => tasks.length;
    const system = Object.fromEntries(
      SYSTEM_VIEWS.map((v) => [v.view, v.view === 'completed' ? 0 : top(open.filter((t) => inView(t, 'sys-' + v.view)))]),
    );
    const byList = new Map<string, number>();
    const byTag = new Map<string, number>();
    const byLoc = new Map<string, number>();
    for (const t of open) {
      byList.set(t.listId, (byList.get(t.listId) ?? 0) + 1);
      for (const tag of t.tags) byTag.set(tag, (byTag.get(tag) ?? 0) + 1);
      if (t.locationId) byLoc.set(t.locationId, (byLoc.get(t.locationId) ?? 0) + 1);
    }
    const bySmart = new Map<string, number>();
    for (const l of smartLists) {
      const q = l.filterQuery ?? '';
      if (!q.trim() || checkQuery(q)) continue;
      try {
        bySmart.set(l.id, runQuery(q, open, { lists, locations }).length);
      } catch {
        /* invalid query: no count */
      }
    }
    return { system, byList, byTag, byLoc, bySmart };
  }, [open, smartLists, lists, locations]);

  async function addList(name: string) {
    const trimmed = name.trim();
    setNewList(null);
    if (!trimmed) return;
    const list = await createList(trimmed);
    await refreshData();
    navigate({ kind: 'list', listId: list.id });
  }

  return (
    <nav className={`sidebar${collapsed ? ' sidebar--collapsed' : ''}`} aria-label="Навигация">
      <div className="sidebar-header">
        <span className="sidebar-logo">Taskly</span>
        <button
          type="button"
          className="sidebar-collapse"
          aria-label={collapsed ? 'Развернуть меню' : 'Свернуть меню'}
          aria-expanded={!collapsed}
          onClick={() => setCollapsed(!collapsed)}
        >
          {collapsed ? '»' : '«'}
        </button>
      </div>

      <div className="sidebar-scroll">
        <div className="sidebar-section" role="group" aria-label="Представления">
          {SYSTEM_VIEWS.map((v) => (
            <Item
              key={v.view}
              view={{ kind: 'system', view: v.view }}
              current={current}
              icon={v.icon}
              label={v.label}
              count={counts.system[v.view]}
              collapsed={collapsed}
            />
          ))}
        </div>

        <div className="sidebar-section" role="group" aria-label="Списки">
          <div className="sidebar-section-title">
            <span>Списки</span>
            <button type="button" className="sidebar-add" aria-label="Новый список" onClick={() => setNewList('')}>
              +
            </button>
          </div>
          {newList !== null && (
            <form
              className="sidebar-new"
              onSubmit={(e) => {
                e.preventDefault();
                // Creation happens once, on blur (Enter blurs the field)
                (e.currentTarget.querySelector('input') as HTMLInputElement | null)?.blur();
              }}
            >
              <input
                className="sidebar-new-input"
                aria-label="Название нового списка"
                autoFocus
                value={newList}
                onChange={(e) => setNewList(e.target.value)}
                onBlur={() => void addList(newList)}
                onKeyDown={(e) => e.key === 'Escape' && setNewList(null)}
              />
            </form>
          )}
          {userLists.map((l) => (
            <Item
              key={l.id}
              view={{ kind: 'list', listId: l.id }}
              current={current}
              icon={l.color ? '●' : '○'}
              label={l.name}
              count={counts.byList.get(l.id)}
              collapsed={collapsed}
            />
          ))}
        </div>

        <div className="sidebar-section" role="group" aria-label="Умные списки">
          <div className="sidebar-section-title">
            <span>Умные списки</span>
            <button type="button" className="sidebar-add" aria-label="Новый умный список" onClick={() => navigate({ kind: 'smart-lists' })}>
              +
            </button>
          </div>
          {smartLists.map((l) => (
            <Item
              key={l.id}
              view={{ kind: 'smart', listId: l.id }}
              current={current}
              icon="🔎"
              label={l.name}
              count={counts.bySmart.get(l.id)}
              collapsed={collapsed}
            />
          ))}
        </div>

        {tagNames.length > 0 && (
          <div className="sidebar-section" role="group" aria-label="Теги">
            <div className="sidebar-section-title">
              <span>Теги</span>
            </div>
            <div className="sidebar-tags">
              {tagNames.map((tag) => {
                const view: AppView = { kind: 'tag', tag };
                const active = sameView(view, current);
                return (
                  <button
                    key={tag}
                    type="button"
                    className={`sidebar-tag${active ? ' sidebar-tag--active' : ''}`}
                    aria-current={active ? 'page' : undefined}
                    onClick={() => navigate(view)}
                  >
                    #{tag}
                    {counts.byTag.get(tag) ? <span className="sidebar-tag-count">{counts.byTag.get(tag)}</span> : null}
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {locations.length > 0 && (
          <div className="sidebar-section" role="group" aria-label="Места">
            <div className="sidebar-section-title">
              <span>Места</span>
            </div>
            {locations.map((l) => (
              <Item
                key={l.id}
                view={{ kind: 'location', locationId: l.id }}
                current={current}
                icon="📍"
                label={l.name}
                count={counts.byLoc.get(l.id)}
                collapsed={collapsed}
              />
            ))}
          </div>
        )}
      </div>

      <div className="sidebar-footer">
        <AuthPanel />
        <div className="sidebar-sync-status" role="status">
          {isOnline ? 'В сети' : 'Офлайн'}
        </div>
        <div className="sidebar-nav-links">
          <Item view={{ kind: 'settings' }} current={current} icon="⚙️" label="Настройки" collapsed={collapsed} />
          <Item view={{ kind: 'onboarding' }} current={current} icon="🎓" label="Знакомство и шпаргалка" collapsed={collapsed} />
        </div>
      </div>
    </nav>
  );
};

export default Sidebar;
