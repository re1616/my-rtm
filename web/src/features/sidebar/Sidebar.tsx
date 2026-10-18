// Taskly Web — Sidebar with system views and navigation
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
    label: 'Входящие',
    icon: '\u{1F4E5}',
    view: { kind: 'system', view: 'inbox' },
  },
  {
    id: 'today',
    label: 'Сегодня',
    icon: '\u{1F4C5}',
    view: { kind: 'system', view: 'today' },
  },
  {
    id: 'overdue',
    label: 'Просрочено',
    icon: '\u26A0\uFE0F',
    view: { kind: 'system', view: 'overdue' },
  },
  {
    id: 'week',
    label: 'Неделя',
    icon: '\u{1F4C6}',
    view: { kind: 'system', view: 'week' },
  },
  {
    id: 'all',
    label: 'Все задачи',
    icon: '\u{1F4CB}',
    view: { kind: 'system', view: 'all' },
  },
  {
    id: 'completed',
    label: 'Выполненные',
    icon: '\u2705',
    view: { kind: 'system', view: 'completed' },
  },
];

const Sidebar: React.FC<{ currentView: AppView }> = ({ currentView }) => {
  const storeState = store.getState();
  const tasks = storeState.tasks;
  const lists = storeState.lists;

  function countForView(view: AppView): number {
    if (view.kind === 'system') {
      if (view.view === 'completed') {
        return tasks.filter((t) => !!t.completedAt).length;
      }
      return tasks.filter((t) => !t.completedAt).length;
    }
    if (view.kind === 'list' || view.kind === 'smart') {
      return tasks.filter((t) => t.listId === view.listId && !t.completedAt).length;
    }
    return 0;
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
    if (view.kind === 'smart') {
      return (
        currentView.kind === 'smart' && currentView.listId === view.listId
      );
    }
    return false;
  }

  function navigateTo(view: AppView) {
    store.getState().setCurrentView(view);
    window.dispatchEvent(
      new CustomEvent('taskly-navigate', { detail: view }),
    );
  }

  function isPageActive(kind: string): boolean {
    return currentView.kind === kind;
  }

  return (
    <nav className="sidebar">
      <div className="sidebar-header">
        <h1 className="sidebar-logo">Taskly</h1>
      </div>

      <div className="sidebar-section">
        <div className="sidebar-section-title">
          Представления
        </div>
        {systemViews.map((sv) => (
          <button
            key={sv.id}
            className={`sidebar-item ${
              isActive(sv.view) ? 'sidebar-item--active' : ''
            }`}
            onClick={() => navigateTo(sv.view)}
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
            Списки
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
                  onClick={() => navigateTo(listView)}
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
          {storeState.isOnline ? 'В сети' : 'Офлайн'}
        </div>
        <div className="sidebar-nav-links">
          <button
            className={`sidebar-nav-link ${
              isPageActive('smart-lists') ? 'sidebar-item--active' : ''
            }`}
            onClick={() => navigateTo({ kind: 'smart-lists' })}
          >
            🔍 Умные списки
          </button>
          <button
            className={`sidebar-nav-link ${
              isPageActive('settings') ? 'sidebar-item--active' : ''
            }`}
            onClick={() => navigateTo({ kind: 'settings' })}
          >
            ⚙️ Настройки
          </button>
          <button
            className={`sidebar-nav-link ${
              isPageActive('onboarding') ? 'sidebar-item--active' : ''
            }`}
            onClick={() => navigateTo({ kind: 'onboarding' })}
          >
            🎓 Обучение
          </button>
        </div>
      </div>
    </nav>
  );
};

export default Sidebar;