// Taskly Web — Main Application Shell
import React, { useState, useEffect } from 'react';
import { useStore } from 'zustand';
import Sidebar from './features/sidebar/Sidebar';
import TaskList from './features/tasks/TaskList';
import store from './store/taskStore';
import { initializeDb } from './db/database';
import { getTasksByList, getLists } from './entities/taskRepository';
import { getSession } from './api/client';
import { startSyncService, stopSyncService } from './features/sync/SyncService';
import type { AppView } from './types';

function getListIdForView(view: AppView): string {
  if (view.kind === 'system') {
    return 'sys-' + view.view;
  }
  return view.listId;
}

const App: React.FC = () => {
  // Subscribe to the store so repository refreshes (store.setTasks) re-render the list
  const tasks = useStore(store, (s) => s.tasks);
  const setTasks = store.getState().setTasks;
  const [view, setView] = useState(store.getState().currentView);
  const [isOnline, setIsOnline] = useState(store.getState().isOnline);
  const [isInitialized, setIsInitialized] = useState(false);

  // Initialize database on mount
  useEffect(() => {
    async function init() {
      await initializeDb();
      const allLists = await getLists();
      const initialView = store.getState().currentView;
      const listId = getListIdForView(initialView);
      const allTasks = await getTasksByList(listId);

      store.getState().setLists(allLists);
      store.getState().setTasks(allTasks);
      store.getState().setSession(await getSession());
      setIsInitialized(true);

      // Background sync: pushes the offline queue, pulls server changes, retries on reconnect
      startSyncService(async (state) => {
        store.getState().setSyncState(state);
        const current = getListIdForView(store.getState().currentView);
        store.getState().setTasks(await getTasksByList(current, { showCompleted: store.getState().showCompleted }));
      });
    }
    init();
    return () => stopSyncService();
  }, []);

  // Listen for navigation events
  useEffect(() => {
    function handleNavigate(e: Event) {
      const customEvent = e as CustomEvent;
      const newView = customEvent.detail as AppView;
      setView(newView);

      const listId = getListIdForView(newView);
      getTasksByList(listId).then((newTasks) => {
        setTasks(newTasks);
      });
    }

    window.addEventListener('taskly-navigate', handleNavigate);
    return () =>
      window.removeEventListener('taskly-navigate', handleNavigate);
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
    // Use browser's online/offline events
    window.addEventListener('online', goOnline as EventListener);
    window.addEventListener('offline', goOffline as EventListener);
    return () => {
      window.removeEventListener('online', goOnline as EventListener);
      window.removeEventListener('offline', goOffline as EventListener);
    };
  }, []);

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
    <div className="app">
      <Sidebar currentView={view} />
      <main className="app-main">
        <TaskList view={view} tasks={tasks} isOnline={isOnline} />
      </main>
    </div>
  );
};

export default App;