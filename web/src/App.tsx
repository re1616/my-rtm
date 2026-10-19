// Taskly Web — application shell (SPEC 4.1): top bar, sidebar, task list, task card.
import React, { useEffect, useState } from 'react';
import { useStore } from 'zustand';
import Sidebar from './features/sidebar/Sidebar';
import TopBar from './features/app/TopBar';
import TaskListView from './features/tasks/TaskListView';
import TaskCard from './features/tasks/TaskCard';
import SmartListBuilder from './features/lists/SmartListBuilder';
import SettingsPage from './features/settings/SettingsPage';
import OnboardingFlow from './features/onboarding/OnboardingFlow';
import Dialogs from './features/dialogs/Dialogs';
import UndoToast from './features/undo/UndoToast';
import UpdateBanner from './features/sync/UpdateBanner';
import store from './store/taskStore';
import { initializeDb } from './db/database';
import { refreshData, installViewRecompute } from './features/app/data';
import { installNotificationHandlers } from './features/push/PushService';
import { loadPrefs } from './features/settings/prefs';
import { initHotkeys, destroyHotkeys } from './features/hotkeys/Hotkeys';
import { t } from './i18n/i18n';

const App: React.FC = () => {
  const view = useStore(store, (s) => s.currentView);
  const openTaskId = useStore(store, (s) => s.openTaskId);
  // Re-render everything when the language changes
  const locale = useStore(store, (s) => s.prefs?.locale ?? 'ru');
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      await initializeDb();
      const prefs = await loadPrefs();
      installViewRecompute();
      await refreshData();
      initHotkeys();
      // First launch: the three onboarding steps (WEB-F11)
      if (!prefs.onboarded && store.getState().allTasks.length === 0) store.getState().setCurrentView({ kind: 'onboarding' });
      if (alive) setReady(true);
    })();
    const offNotifications = installNotificationHandlers();
    return () => {
      alive = false;
      destroyHotkeys();
      offNotifications();
    };
  }, []);

  // Server data or another tab changed IndexedDB: reload what is on screen
  useEffect(() => {
    const handler = () => void refreshData();
    window.addEventListener('taskly-data-changed', handler);
    return () => window.removeEventListener('taskly-data-changed', handler);
  }, []);

  useEffect(() => {
    const online = () => store.getState().setIsOnline(true);
    const offline = () => store.getState().setIsOnline(false);
    window.addEventListener('online', online);
    window.addEventListener('offline', offline);
    return () => {
      window.removeEventListener('online', online);
      window.removeEventListener('offline', offline);
    };
  }, []);

  if (!ready) {
    return (
      <div className="app app--loading" aria-busy="true">
        <div className="app-loading">
          <h1>Taskly</h1>
          <p>{t('Загрузка…')}</p>
        </div>
      </div>
    );
  }

  const page =
    view.kind === 'settings' ? <SettingsPage /> : view.kind === 'onboarding' ? <OnboardingFlow /> : view.kind === 'smart-lists' ? <SmartListBuilder /> : <TaskListView />;
  const showCard = !!openTaskId && view.kind !== 'settings' && view.kind !== 'onboarding' && view.kind !== 'smart-lists';

  return (
    <div key={locale} className={`app${showCard ? ' app--has-detail' : ''}`}>
      <a className="skip-link" href="#main">{t('К списку задач')}</a>
      <Sidebar />
      <div className="app-column">
        <TopBar />
        <UpdateBanner />
        <main className="app-main" id="main">
          {page}
        </main>
      </div>
      {showCard && (
        <div className="app-detail">
          <TaskCard />
        </div>
      )}
      <Dialogs />
      <UndoToast />
    </div>
  );
};

export default App;
