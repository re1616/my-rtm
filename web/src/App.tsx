// Taskly Web — Main Application Shell
// Three-panel layout: sidebar + content + detail panel
import React, { useState, useEffect, useCallback } from 'react';
import Sidebar from './features/sidebar/Sidebar';
import TaskList from './features/tasks/TaskList';
import TaskDetailPage from './features/tasks/TaskDetailPage';
import SmartListBuilder from './features/lists/SmartListBuilder';
import SettingsPage from './features/settings/SettingsPage';
import OnboardingFlow from './features/onboarding/OnboardingFlow';
import MassOperationsBar from './features/massops/MassOperationsBar';
import store from './store/taskStore';
import { initializeDb } from './db/database';
import { getTasksByList, getLists } from './entities/taskRepository';
import { registerPush } from './features/push/PushService';
import { initHotkeys, destroyHotkeys } from './features/hotkeys/Hotkeys';
import type { AppView } from './types';

function getListIdForView(view: AppView): string {
  if (view.kind === 'system') return 'sys-' + view.view;
  if (view.kind === 'list' || view.kind === 'smart') return view.listId;
  return 'sys-inbox';
}

const App: React.FC = () => {
  const [tasks, setTasks] = useState(store.getState().tasks);
  const [lists, setLists] = useState(store.getState().lists);
  const [view, setView] = useState(store.getState().currentView);
  const [isOnline, setIsOnline] = useState(store.getState().isOnline);
  const [isInitialized, setIsInitialized] = useState(false);

  // Detail panel state: which task is open in the right panel
  const [detailTaskId, setDetailTaskId] = useState<string | null>(null);
  const [selectedCount, setSelectedCount] = useState(
    store.getState().selectedTaskIds.length,
  );

  // Load initial data
  useEffect(() => {
    async function init() {
      await initializeDb();
      const allLists = await getLists();
      const initialView = store.getState().currentView;
      const listId = getListIdForView(initialView);
      const allTasks = await getTasksByList(listId);

      store.getState().setLists(allLists);
      store.getState().setTasks(allTasks);
      setLists(allLists);
      setTasks(allTasks);

      // Init push and hotkeys in background
      registerPush().catch(() => {});
      initHotkeys();

      setIsInitialized(true);
    }
    init();
    return () => {
      destroyHotkeys();
    };
  }, []);

  // Subscribe to store changes for selectedCount
  useEffect(() => {
    const unsub = store.subscribe((state) => {
      setSelectedCount(state.selectedTaskIds.length);
    });
    return unsub;
  }, []);

  // Listen for navigation events
  useEffect(() => {
    function handleNavigate(e: Event) {
      const customEvent = e as CustomEvent;
      const newView = customEvent.detail as AppView;
      setView(newView);

      // Close detail panel on view change unless navigating to task-detail
      if (newView.kind !== 'task-detail') {
        setDetailTaskId(null);
      }

      if (newView.kind === 'task-detail') {
        setDetailTaskId(newView.taskId);
        return;
      }

      // Load tasks for list/system views
      const listId = getListIdForView(newView);
      getTasksByList(listId).then((newTasks) => {
        setTasks(newTasks);
      });
    }

    window.addEventListener('taskly-navigate', handleNavigate);
    return () =>
      window.removeEventListener('taskly-navigate', handleNavigate);
  }, []);

  // Handle hotkey events
  useEffect(() => {
    function handleHotkey(e: Event) {
      const customEvent = e as CustomEvent;
      switch (customEvent.detail?.action) {
        case 'close':
          setDetailTaskId(null);
          store.getState().clearSelection();
          break;
        case 'delete-selected':
          // In R2: confirm and delete
          break;
        case 'select-all':
          // In R2: select all visible tasks
          break;
        case 'show-shortcuts':
          // In R2: show shortcut help modal
          break;
      }
    }

    window.addEventListener('taskly-hotkey', handleHotkey);
    return () =>
      window.removeEventListener('taskly-hotkey', handleHotkey);
  }, []);

  // Monitor online status
  useEffect(() => {
    function goOnline() {
      setIsOnline(true);
      store.getState().setIsOnline(true);
    }
    function goOffline() {
      setIsOnline(false);
      store.getState().setIsOnline(false);
    }
    window.addEventListener('online', goOnline as EventListener);
    window.addEventListener('offline', goOffline as EventListener);
    return () => {
      window.removeEventListener('online', goOnline as EventListener);
      window.removeEventListener('offline', goOffline as EventListener);
    };
  }, []);

  // Mass ops handlers (stubs)
  const handleMassComplete = useCallback(() => {
    // In R2: batch complete selected tasks
  }, []);
  const handleMassDelete = useCallback(() => {
    // In R2: batch delete selected tasks
  }, []);
  const handleMassMove = useCallback(() => {
    // In R2: batch move to list
  }, []);
  const handleMassSetPriority = useCallback((_priority: number) => {
    // In R2: batch set priority
  }, []);
  const handleMassAddTag = useCallback((_tag: string) => {
    // In R2: batch add tag
  }, []);
  const handleClearSelection = useCallback(() => {
    store.getState().clearSelection();
  }, []);

  // Navigate to task detail
  const handleTaskClick = useCallback((taskId: string) => {
    const detailView: AppView = { kind: 'task-detail', taskId };
    store.getState().setCurrentView(detailView);
    setView(detailView);
    setDetailTaskId(taskId);
    // Also dispatch event for any other listeners
    window.dispatchEvent(
      new CustomEvent('taskly-navigate', { detail: detailView }),
    );
  }, []);

  const handleCloseDetail = useCallback(() => {
    setDetailTaskId(null);
    store.getState().clearSelection();
  }, []);

  // ── Render helpers ──

  function renderContent() {
    switch (view.kind) {
      case 'settings':
        return <SettingsPage />;
      case 'onboarding':
        return <OnboardingFlow />;
      case 'smart-lists':
        return <SmartListBuilder />;
      case 'task-detail':
        // Show TaskList in left panel, detail in right (handled by layout)
        return <TaskList view={view} tasks={tasks} isOnline={isOnline} onTaskClick={handleTaskClick} />;
      default:
        // system / list / smart views
        return <TaskList view={view} tasks={tasks} isOnline={isOnline} onTaskClick={handleTaskClick} />;
    }
  }

  if (!isInitialized) {
    return (
      <div className="app app--loading">
        <div className="app-loading">
          <h1>Taskly</h1>
          <p>Загрузка...</p>
        </div>
      </div>
    );
  }

  return (
    <div className={`app ${detailTaskId ? 'app--has-detail' : ''}`}>
      <Sidebar currentView={view} />
      <main className="app-main">
        <MassOperationsBar
          selectedCount={selectedCount}
          onComplete={handleMassComplete}
          onDelete={handleMassDelete}
          onMove={handleMassMove}
          onSetPriority={handleMassSetPriority}
          onAddTag={handleMassAddTag}
          onClearSelection={handleClearSelection}
        />
        {renderContent()}
      </main>
      {detailTaskId && (
        <aside className="app-detail">
          <TaskDetailPage taskId={detailTaskId} onClose={handleCloseDetail} />
        </aside>
      )}
    </div>
  );
};

export default App;