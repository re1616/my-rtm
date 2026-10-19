// Taskly Web — account & sync status (sidebar footer).
// The app works fully offline without an account; signing in enables server sync.
import React, { useEffect, useRef, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import {
  ApiError,
  NetworkError,
  forgotPassword,
  login,
  loginWithGoogle,
  register,
  resetPassword,
  setSession,
} from '../../api/client';
import { clearRejections, requestSync, type SyncState } from './SyncService';
import { msg, t } from '../../i18n/i18n';

const STATE_LABEL: Record<SyncState, string> = {
  idle: msg('Синхронизировано'),
  offline: msg('Офлайн — изменения в очереди'),
  unauthenticated: msg('Требуется вход'),
  error: msg('Ошибка синхронизации'),
};

const ENTITY_LABEL: Record<string, string> = { task: msg('задача'), list: msg('список'), tag: msg('тег'), location: msg('место'), note: msg('заметка'), attachment: msg('вложение') };

type Mode = 'login' | 'totp' | 'forgot' | 'forgot-sent' | 'reset' | 'reset-done';

/** Token from a password-reset link (/reset-password?token=…), if the page was opened from one. */
function resetTokenFromUrl(): string | null {
  if (typeof window === 'undefined' || window.location.pathname !== '/reset-password') return null;
  return new URLSearchParams(window.location.search).get('token');
}

function describe(e: unknown, fallback: string): string {
  if (e instanceof NetworkError) return t('Сервер недоступен');
  if (e instanceof ApiError) return e.message;
  return fallback;
}

const AuthPanel: React.FC = () => {
  const session = useStore(store, (s) => s.session);
  const syncState = useStore(store, (s) => s.syncState);
  const rejections = useStore(store, (s) => s.rejections);
  const snapshotLoaded = useStore(store, (s) => s.snapshotLoaded);

  const [resetToken] = useState(resetTokenFromUrl);
  const [mode, setMode] = useState<Mode>(resetToken ? 'reset' : 'login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [pendingGoogleToken, setPendingGoogleToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<void>, fallback: string) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'totp_required') {
        setMode('totp');
      } else {
        setError(describe(e, fallback));
      }
    } finally {
      setBusy(false);
    }
  }

  async function signedIn(s: Awaited<ReturnType<typeof login>>) {
    store.getState().setSession(s);
    setPassword('');
    setCode('');
    setPendingGoogleToken(null);
    setMode('login');
    requestSync();
  }

  const submitLogin = () => run(async () => signedIn(await login({}, email, password)), t('Не удалось войти'));
  const submitRegister = () => run(async () => signedIn(await register({}, email, password)), t('Не удалось зарегистрироваться'));
  const submitCode = () =>
    run(async () => {
      const s = pendingGoogleToken
        ? await loginWithGoogle({}, pendingGoogleToken, code)
        : await login({}, email, password, code);
      await signedIn(s);
    }, t('Неверный код'));
  const submitForgot = () =>
    run(async () => {
      await forgotPassword({}, email);
      setMode('forgot-sent');
    }, t('Не удалось отправить письмо'));
  const submitReset = () =>
    run(async () => {
      await resetPassword({}, resetToken ?? '', password);
      setPassword('');
      window.history.replaceState(null, '', '/');
      setMode('reset-done');
    }, t('Ссылка недействительна или устарела'));

  // Google Identity Services button (shown only when a client id is configured)
  const googleButton = useRef<HTMLDivElement>(null);
  const googleClientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
  useEffect(() => {
    if (!googleClientId || session || mode !== 'login' || !googleButton.current) return;
    const target = googleButton.current;
    const onCredential = (credential: string) =>
      run(async () => {
        try {
          await signedIn(await loginWithGoogle({}, credential));
        } catch (e) {
          if (e instanceof ApiError && e.code === 'totp_required') setPendingGoogleToken(credential);
          throw e;
        }
      }, t('Не удалось войти через Google'));
    loadGoogleIdentity().then((google) => {
      google.accounts.id.initialize({ client_id: googleClientId, callback: (r: { credential: string }) => void onCredential(r.credential) });
      google.accounts.id.renderButton(target, { theme: 'outline', size: 'medium', text: 'signin_with' });
    }).catch(() => {
      /* offline or blocked: email sign-in still works */
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [googleClientId, session, mode]);

  async function logout() {
    await setSession(null);
    store.getState().setSession(null);
    store.getState().setSyncState('unauthenticated');
  }

  if (session && mode !== 'reset') {
    return (
      <div className="auth-panel">
        <div className="auth-panel-user" title={session.email}>{session.email ?? t('Аккаунт')}</div>
        <div className={`auth-panel-state auth-panel-state--${syncState}`}>
          {snapshotLoaded !== null ? t('Загрузка данных… {n}', { n: snapshotLoaded }) : t(STATE_LABEL[syncState])}
        </div>
        {rejections.length > 0 && (
          <div className="auth-panel-error" role="alert">
            {t('Сервер отклонил изменений: {n}. Они отменены на этом устройстве.', { n: rejections.length })}
            <ul className="auth-panel-rejections">
              {rejections.slice(0, 3).map((r) => (
                <li key={`${r.entityId}-${r.at}`}>
                  {ENTITY_LABEL[r.entity] ? t(ENTITY_LABEL[r.entity]) : r.entity}: {r.reason}
                </li>
              ))}
            </ul>
            <button className="auth-panel-btn auth-panel-btn--ghost" onClick={() => void clearRejections()}>
              {t('Понятно')}
            </button>
          </div>
        )}
        <div className="auth-panel-actions">
          <button className="auth-panel-btn" onClick={() => requestSync()}>
            {t('Синхронизировать')}
          </button>
          <button className="auth-panel-btn auth-panel-btn--ghost" onClick={() => void logout()}>
            {t('Выйти')}
          </button>
        </div>
      </div>
    );
  }

  const field = (type: string, placeholder: string, value: string, set: (v: string) => void, autoComplete: string) => (
    <input
      className="auth-panel-input"
      type={type}
      placeholder={placeholder}
      autoComplete={autoComplete}
      value={value}
      onChange={(e) => set(e.target.value)}
      required
    />
  );
  const errorLine = error && <div className="auth-panel-error" role="alert">{error}</div>;

  if (mode === 'reset') {
    return (
      <form className="auth-panel" onSubmit={(e) => { e.preventDefault(); void submitReset(); }}>
        <div className="auth-panel-state">{t('Новый пароль (не короче 8 символов)')}</div>
        {field('password', t('новый пароль'), password, setPassword, 'new-password')}
        {errorLine}
        <button className="auth-panel-btn" type="submit" disabled={busy || password.length < 8}>{t('Сохранить пароль')}</button>
      </form>
    );
  }

  if (mode === 'totp') {
    return (
      <form className="auth-panel" onSubmit={(e) => { e.preventDefault(); void submitCode(); }}>
        <div className="auth-panel-state">{t('Введите код из приложения-аутентификатора')}</div>
        <input
          className="auth-panel-input"
          inputMode="numeric"
          autoComplete="one-time-code"
          placeholder="123456"
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
          required
        />
        {errorLine}
        <div className="auth-panel-actions">
          <button className="auth-panel-btn" type="submit" disabled={busy || code.length !== 6}>{t('Войти')}</button>
          <button className="auth-panel-btn auth-panel-btn--ghost" type="button" onClick={() => { setMode('login'); setCode(''); setPendingGoogleToken(null); }}>
            {t('Назад')}
          </button>
        </div>
      </form>
    );
  }

  if (mode === 'forgot' || mode === 'forgot-sent') {
    return (
      <form className="auth-panel" onSubmit={(e) => { e.preventDefault(); void submitForgot(); }}>
        {mode === 'forgot-sent' ? (
          <div className="auth-panel-state">{t('Если этот адрес зарегистрирован, мы отправили на него ссылку для сброса пароля.')}</div>
        ) : (
          <>
            <div className="auth-panel-state">{t('Пришлём ссылку для сброса пароля')}</div>
            {field('email', 'email', email, setEmail, 'email')}
          </>
        )}
        {errorLine}
        <div className="auth-panel-actions">
          {mode === 'forgot' && <button className="auth-panel-btn" type="submit" disabled={busy}>{t('Отправить')}</button>}
          <button className="auth-panel-btn auth-panel-btn--ghost" type="button" onClick={() => setMode('login')}>
            {t('Ко входу')}
          </button>
        </div>
      </form>
    );
  }

  return (
    <form className="auth-panel" onSubmit={(e) => { e.preventDefault(); void submitLogin(); }}>
      <div className="auth-panel-state">
        {mode === 'reset-done' ? t('Пароль изменён — войдите с новым паролем') : t('Войдите, чтобы синхронизировать задачи')}
      </div>
      {field('email', 'email', email, setEmail, 'email')}
      {field('password', t('пароль'), password, setPassword, 'current-password')}
      {errorLine}
      <div className="auth-panel-actions">
        <button className="auth-panel-btn" type="submit" disabled={busy}>{t('Войти')}</button>
        <button className="auth-panel-btn auth-panel-btn--ghost" type="button" disabled={busy} onClick={() => void submitRegister()}>
          {t('Регистрация')}
        </button>
      </div>
      <button className="auth-panel-link" type="button" onClick={() => setMode('forgot')}>
        {t('Забыли пароль?')}
      </button>
      {googleClientId && <div className="auth-panel-google" ref={googleButton} />}
    </form>
  );
};

interface GoogleIdentity {
  accounts: {
    id: {
      initialize(options: { client_id: string; callback: (response: { credential: string }) => void }): void;
      renderButton(parent: HTMLElement, options: Record<string, string>): void;
    };
  };
}

let googleScript: Promise<GoogleIdentity> | null = null;

function loadGoogleIdentity(): Promise<GoogleIdentity> {
  googleScript ??= new Promise((resolve, reject) => {
    const existing = (window as unknown as { google?: GoogleIdentity }).google;
    if (existing?.accounts) return resolve(existing);
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = () => resolve((window as unknown as { google: GoogleIdentity }).google);
    script.onerror = () => {
      googleScript = null;
      reject(new Error('Google Identity Services failed to load'));
    };
    document.head.appendChild(script);
  });
  return googleScript;
}

export default AuthPanel;
