// Taskly Web — account & sync status (sidebar footer).
// The app works fully offline without an account; signing in enables server sync.
import React, { useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { ApiError, NetworkError, login, register, setSession } from '../../api/client';
import { triggerSync, type SyncState } from './SyncService';

const STATE_LABEL: Record<SyncState, string> = {
  idle: 'Синхронизировано',
  offline: 'Офлайн — изменения в очереди',
  unauthenticated: 'Требуется вход',
  error: 'Ошибка синхронизации',
};

const AuthPanel: React.FC = () => {
  const session = useStore(store, (s) => s.session);
  const syncState = useStore(store, (s) => s.syncState);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(kind: 'login' | 'register') {
    setBusy(true);
    setError(null);
    try {
      const s = kind === 'login' ? await login({}, email, password) : await register({}, email, password);
      store.getState().setSession(s);
      setPassword('');
      store.getState().setSyncState(await triggerSync());
    } catch (e) {
      if (e instanceof NetworkError) setError('Сервер недоступен');
      else if (e instanceof ApiError) setError(e.message);
      else setError('Не удалось войти');
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    await setSession(null);
    store.getState().setSession(null);
    store.getState().setSyncState('unauthenticated');
  }

  if (session) {
    return (
      <div className="auth-panel">
        <div className="auth-panel-user" title={session.email}>{session.email ?? 'Аккаунт'}</div>
        <div className={`auth-panel-state auth-panel-state--${syncState}`}>{STATE_LABEL[syncState]}</div>
        <div className="auth-panel-actions">
          <button className="auth-panel-btn" onClick={() => void triggerSync().then(store.getState().setSyncState)}>
            Синхронизировать
          </button>
          <button className="auth-panel-btn auth-panel-btn--ghost" onClick={() => void logout()}>
            Выйти
          </button>
        </div>
      </div>
    );
  }

  return (
    <form
      className="auth-panel"
      onSubmit={(e) => {
        e.preventDefault();
        void submit('login');
      }}
    >
      <div className="auth-panel-state">Войдите, чтобы синхронизировать задачи</div>
      <input
        className="auth-panel-input"
        type="email"
        placeholder="email"
        autoComplete="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        required
      />
      <input
        className="auth-panel-input"
        type="password"
        placeholder="пароль"
        autoComplete="current-password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        required
      />
      {error && <div className="auth-panel-error">{error}</div>}
      <div className="auth-panel-actions">
        <button className="auth-panel-btn" type="submit" disabled={busy}>
          Войти
        </button>
        <button
          className="auth-panel-btn auth-panel-btn--ghost"
          type="button"
          disabled={busy}
          onClick={() => void submit('register')}
        >
          Регистрация
        </button>
      </div>
    </form>
  );
};

export default AuthPanel;
