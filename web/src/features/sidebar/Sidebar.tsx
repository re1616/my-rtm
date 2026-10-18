// Taskly Web — Sidebar with system views
import React from 'react';
import type { AppView } from '../../types';
import store from '../../store/taskStore';
import AuthPanel from '../sync/AuthPanel';

const systemViews: {
  id: string;
  label: string;
  icon: string;
  view: AppView;
}[] = [
  {
    id: 'inbox',
    label: '\u0412\u0445\u043E\u0434\u044F\u0449\u0438\u0435',
    icon: '\u{1F4E5}',
    view: { kind: 'system', view: 'inbox' },
  },
  {
    id: 'today',
    label: '\u0421\u0435\u0433\u043E\u0434\u043D\u044F',
    icon: '\u{1F4C5}',
    view: { kind: 'system', view: 'today' },
  },
  {
    id: 'overdue',
    label: '\u041F\u0440\u043E\u0441\u0440\u043E\u0447\u0435\u043D\u043E',
    icon: '\u26A0\uFE0F',
    view: { kind: 'system', view: 'overdue' },
  },
  {
    id: 'week',
    label: '\u041D\u0435\u0434\u0435\u043B\u044F',
    icon: '\u{1F4C6}',
    view: { kind: 'system', view: 'week' },
  },
  {
    id: 'all',
    label: '\u0412\u0441\u0435 \u0437\u0430\u0434\u0430\u0447\u0438',
    icon: '\u{1F4CB}',
    view: { kind: 'system', view: 'all' },
  },
  {
    id: 'completed',
    label: '\u0412\u044B\u043F\u043E\u043B\u043D\u0435\u043D\u043D\u044B\u0435',
    icon: '\u2705',
    view: { kind: 'system', view: 'completed' },
  },
];

const Sidebar: React.FC<{ currentView: AppView }> = ({ currentView }) => {
  const storeState = store.getState();
  const tasks = storeState.tasks;
  const lists = storeState.lists;

  function countForView(view: AppView): number {
    // Simple count based on current tasks shown; in production would filter properly
    if (view.kind === 'system') {
      if (view.view === 'completed') {
        return tasks.filter((t) => !!t.completedAt).length;
      }
      return tasks.filter((t) => !t.completedAt).length;
    }
    return tasks.filter((t) => t.listId === view.listId && !t.completedAt)
      .length;
  }

  function isActive(view: AppView): boolean {
    if (currentView.kind !== view.kind) return false;
    if (view.kind === 'system') {
      return (
        currentView.kind === 'system' && currentView.view === view.view
      );
    }
    if (view.kind === 'list') {
      return (
        currentView.kind === 'list' && currentView.listId === view.listId
      );
    }
    return false;
  }

  return (
    <nav className="sidebar">
      <div className="sidebar-header">
        <h1 className="sidebar-logo">Taskly</h1>
      </div>

      <div className="sidebar-section">
        <div className="sidebar-section-title">
          \u041F\u0440\u0435\u0434\u0441\u0442\u0430\u0432\u043B\u0435\u043D\u0438\u044F
        </div>
        {systemViews.map((sv) => (
          <button
            key={sv.id}
            className={`sidebar-item ${
              isActive(sv.view) ? 'sidebar-item--active' : ''
            }`}
            onClick={() => {
              store.getState().setCurrentView(sv.view);
              // Trigger task reload via the app-layer
              window.dispatchEvent(
                new CustomEvent('taskly-navigate', { detail: sv.view }),
              );
            }}
          >
            <span className="sidebar-item-icon">{sv.icon}</span>
            <span className="sidebar-item-label">{sv.label}</span>
            <span className="sidebar-item-count">{countForView(sv.view)}</span>
          </button>
        ))}
      </div>

      {lists.length > 0 && (
        <div className="sidebar-section">
          <div className="sidebar-section-title">
            \u0421\u043F\u0438\u0441\u043A\u0438
          </div>
          {lists
            .filter((l) => !l.isSystem)
            .map((list) => {
              const listView: AppView = { kind: 'list', listId: list.id };
              return (
                <button
                  key={list.id}
                  className={`sidebar-item ${
                    isActive(listView) ? 'sidebar-item--active' : ''
                  }`}
                  onClick={() => {
                    store.getState().setCurrentView(listView);
                    window.dispatchEvent(
                      new CustomEvent('taskly-navigate', { detail: listView }),
                    );
                  }}
                >
                  <span className="sidebar-item-icon">{list.color ? '\u25CF' : '\u25CB'}</span>
                  <span className="sidebar-item-label">{list.name}</span>
                  <span className="sidebar-item-count">
                    {countForView(listView)}
                  </span>
                </button>
              );
            })}
        </div>
      )}

      <div className="sidebar-footer">
        <AuthPanel />
        <div className="sidebar-sync-status">
          {storeState.isOnline ? '\u0412 \u0441\u0435\u0442\u0438' : '\u041E\u0444\u043B\u0430\u0439\u043D'}
        </div>
      </div>
    </nav>
  );
};

export default Sidebar;