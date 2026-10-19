// Taskly Web — two-factor authentication settings (WEB-S3).
import React, { useEffect, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { ApiError, NetworkError, disableTotp, enableTotp, getAccount, setupTotp, type Account } from '../../api/client';
import { t } from '../../i18n/i18n';

const SecuritySettings: React.FC = () => {
  const session = useStore(store, (s) => s.session);
  const [account, setAccount] = useState<Account | null>(null);
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!session) return;
    getAccount({}, session).then(setAccount).catch(() => setAccount(null));
  }, [session]);

  if (!session) return <span className="settings-stub-hint">{t('Войдите, чтобы настроить двухфакторную аутентификацию')}</span>;
  if (!account) return <span className="settings-stub-hint">{t('Загрузка…')}</span>;

  async function act(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof NetworkError ? t('Сервер недоступен') : e instanceof ApiError ? e.message : t('Ошибка'));
    } finally {
      setBusy(false);
    }
  }

  const codeInput = (
    <input
      className="settings-input"
      inputMode="numeric"
      autoComplete="one-time-code"
      placeholder={t('Код из приложения')}
      maxLength={6}
      value={code}
      onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
    />
  );

  if (account.totpEnabled) {
    return (
      <div className="settings-security">
        <div className="settings-row">
          <span className="settings-row-label">2FA</span>
          <span className="settings-row-value">{t('Включена')}</span>
        </div>
        {codeInput}
        <button
          className="settings-btn"
          disabled={busy || code.length !== 6}
          onClick={() => act(async () => {
            await disableTotp({}, session, code);
            setCode('');
            setAccount({ ...account, totpEnabled: false });
          })}
        >
          {t('Отключить')}
        </button>
        {error && <div className="settings-error" role="alert">{error}</div>}
      </div>
    );
  }

  return (
    <div className="settings-security">
      {!setup ? (
        <button className="settings-btn" disabled={busy} onClick={() => act(async () => setSetup(await setupTotp({}, session)))}>
          {t('Включить 2FA')}
        </button>
      ) : (
        <>
          <p className="settings-stub-hint">
            {t('Добавьте аккаунт в приложение-аутентификатор (Google Authenticator, 1Password, Aegis): по ссылке')}
            {t('или вручную по ключу, затем введите код из приложения.')}
          </p>
          <a className="settings-link" href={setup.otpauthUri}>{t('Открыть в приложении')}</a>
          <code className="settings-secret" aria-label={t('Ключ для ручного ввода')}>{setup.secret}</code>
          {codeInput}
          <button
            className="settings-btn"
            disabled={busy || code.length !== 6}
            onClick={() => act(async () => {
              await enableTotp({}, session, code);
              setCode('');
              setSetup(null);
              setAccount({ ...account, totpEnabled: true });
            })}
          >
            {t('Подтвердить')}
          </button>
        </>
      )}
      {error && <div className="settings-error" role="alert">{error}</div>}
    </div>
  );
};

export default SecuritySettings;
