import React from 'react';
import ReactDOM from 'react-dom/client';
import { registerSW } from 'virtual:pwa-register';
import App from './App';
import './styles/global.css';
import store from './store/taskStore';
import { startSyncService } from './features/sync/SyncService';
import { getSession } from './api/client';
import { SwUpdateController } from './features/sync/swUpdateStrategy';

// The signed-in account survives reloads, and a sign-in/out in one tab shows up in the others
const refreshSession = () => void getSession().then((s) => store.getState().setSession(s));
refreshSession();

// Background sync: one leader tab runs it, every tab shows the same state (WEB-S5)
startSyncService((s) => {
  store.getState().setSyncState(s);
  refreshSession();
});

// App updates wait for the user's go-ahead (WEB-S8)
if ('serviceWorker' in navigator) {
  const updates = new SwUpdateController(registerSW);
  updates.onChange((s) => store.getState().setUpdateReady(s === 'update-ready' || s === 'waiting-for-input'));
  (window as unknown as { __tasklyUpdates?: SwUpdateController }).__tasklyUpdates = updates;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
