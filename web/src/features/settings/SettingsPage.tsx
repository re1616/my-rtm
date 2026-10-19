// Taskly Web — settings (WEB-F9, WEB-F8): account, time zone, language, theme, reminders,
// two-factor authentication, data export and account deletion.
import React, { useEffect, useMemo, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import SecuritySettings from './SecuritySettings';
import { updatePrefs } from './prefs';
import { ApiError, NetworkError, deleteAccount, exportAccount, setSession } from '../../api/client';
import { disablePush, enablePush, isPushSupported, pushStatus, sendTestPush, type PushStatus } from '../push/PushService';
import { getDb } from '../../db/database';
import { refreshData } from '../app/data';
import { t } from '../../i18n/i18n';

const OFFSETS = [0, 5, 10, 15, 30, 60, 120, 1440];

/** Each language is named in itself. */
const LANGUAGE_NAMES = { ru: 'Русский', en: 'English' }; // i18n-ignore

function offsetLabel(m: number): string {
  if (m === 0) return t('в момент срока');
  if (m < 60) return t('за {n} мин', { n: m });
  if (m < 1440) return t('за {n} ч', { n: m / 60 });
  return t('за {n} дн.', { n: m / 1440 });
}

function zones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (k: string) => string[] };
  try {
    return intl.supportedValuesOf?.('timeZone') ?? [];
  } catch {
    return [];
  }
}

/** Downloads text as a file. */
function download(name: string, text: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function csvCell(v: unknown): string {
  const s = v == null ? '' : Array.isArray(v) ? v.join(' ') : String(v);
  return /[",\n;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** Local data (works offline and without an account). */
async function localExport() {
  const db = getDb();
  const [tasks, lists, tags, locations, notes, attachments] = await Promise.all([
    db.tasks.toArray(), db.lists.toArray(), db.tags.toArray(), db.locations.toArray(), db.notes.toArray(), db.attachments.toArray(),
  ]);
  return {
    format: 'taskly-export/1',
    exportedAt: new Date().toISOString(),
    lists: lists.filter((l) => !l.isSystem),
    tags,
    locations,
    tasks: tasks.filter((x) => !x.deletedAt),
    notes,
    attachments,
  };
}

async function tasksCsv(): Promise<string> {
  const db = getDb();
  const [tasks, lists] = await Promise.all([db.tasks.toArray(), db.lists.toArray()]);
  const listName = new Map(lists.map((l) => [l.id, l.isSystem ? t(l.name) : l.name]));
  const header = ['id', 'name', 'list', 'priority', 'due', 'start', 'repeat', 'tags', 'completed', 'created'];
  const rows = tasks
    .filter((x) => !x.deletedAt)
    .map((x) => [x.id, x.name, listName.get(x.listId) ?? '', x.priority ?? '', x.dueDate ?? '', x.startDate ?? '', x.repeatRule ?? '', x.tags, x.completedAt ?? '', x.createdAt].map(csvCell).join(','));
  return [header.join(','), ...rows].join('\n');
}

/** After the account is deleted (or on explicit wipe): nothing of it stays on this device. */
export async function clearLocalData(): Promise<void> {
  const db = getDb();
  await Promise.all([db.tasks, db.lists, db.tags, db.locations, db.notes, db.attachments, db.syncQueue, db.settings].map((tbl) => tbl.clear()));
  await setSession(null);
}

const Section: React.FC<{ title: string; danger?: boolean; children: React.ReactNode }> = ({ title, danger, children }) => (
  <section className={`settings-section${danger ? ' settings-section--danger' : ''}`} aria-label={title}>
    <h3 className="settings-section-title">{title}</h3>
    <div className="settings-section-body">{children}</div>
  </section>
);

const SettingsPage: React.FC = () => {
  const prefs = useStore(store, (s) => s.prefs);
  const session = useStore(store, (s) => s.session);
  const zoneList = useMemo(() => zones(), []);
  const [push, setPush] = useState<PushStatus | 'busy'>('off');
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    void pushStatus().then(setPush).catch(() => setPush('unsupported'));
  }, []);

  if (!prefs) return null;
  const set = (patch: Parameters<typeof updatePrefs>[0]) => void updatePrefs(patch);

  async function togglePush() {
    setMessage(null);
    setPush('busy');
    try {
      setPush(push === 'on' ? await disablePush() : await enablePush());
    } catch (e) {
      setPush(await pushStatus().catch(() => 'off' as const));
      setMessage(e instanceof NetworkError ? t('Сервер недоступен') : e instanceof ApiError && e.status === 404 ? t('На сервере не настроены уведомления (VAPID)') : t('Не удалось включить уведомления'));
    }
  }

  return (
    <div className="settings-page">
      <h2 className="settings-page-title">{t('Настройки')}</h2>
      <div className="settings-sections">
        <Section title={t('Аккаунт')}>
          <div className="settings-row">
            <span className="settings-row-label">Email</span>
            <span className="settings-row-value">{session?.email ?? t('Вы не вошли — задачи хранятся только на этом устройстве')}</span>
          </div>
        </Section>

        <Section title={t('Язык и время')}>
          <label className="settings-row">
            <span className="settings-row-label">{t('Язык интерфейса')}</span>
            <select className="settings-select" value={prefs.locale} onChange={(e) => set({ locale: e.target.value as 'ru' | 'en' })}>
              <option value="ru">{LANGUAGE_NAMES.ru}</option>
              <option value="en">{LANGUAGE_NAMES.en}</option>
            </select>
          </label>
          <label className="settings-row">
            <span className="settings-row-label">{t('Часовой пояс')}</span>
            <select className="settings-select" value={prefs.timeZone} onChange={(e) => set({ timeZone: e.target.value })}>
              {(zoneList.includes(prefs.timeZone) ? zoneList : [prefs.timeZone, ...zoneList]).map((z) => (
                <option key={z} value={z}>
                  {z}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-row">
            <span className="settings-row-label">{t('Первый день недели')}</span>
            <select className="settings-select" value={prefs.weekStart} onChange={(e) => set({ weekStart: Number(e.target.value) as 1 | 7 })}>
              <option value={1}>{t('Понедельник')}</option>
              <option value={7}>{t('Воскресенье')}</option>
            </select>
          </label>
        </Section>

        <Section title={t('Тема оформления')}>
          <div className="settings-choice" role="radiogroup" aria-label={t('Тема оформления')}>
            {(['system', 'light', 'dark'] as const).map((th) => (
              <label key={th}>
                <input type="radio" name="theme" checked={prefs.theme === th} onChange={() => set({ theme: th })} />
                {th === 'system' ? t('Как в системе') : th === 'light' ? t('Светлая') : t('Тёмная')}
              </label>
            ))}
          </div>
        </Section>

        <Section title={t('Напоминания')}>
          <p className="settings-hint">{t('Для задач со временем — за выбранное время до срока; для задач с датой — в выбранное время в день срока.')}</p>
          <div className="settings-chips" role="group" aria-label={t('Напоминать')}>
            {OFFSETS.map((m) => {
              const on = prefs.reminderOffsets.includes(m);
              return (
                <button
                  key={m}
                  type="button"
                  className={`settings-chip${on ? ' settings-chip--on' : ''}`}
                  aria-pressed={on}
                  onClick={() => set({ reminderOffsets: on ? prefs.reminderOffsets.filter((x) => x !== m) : [...prefs.reminderOffsets, m].sort((a, b) => a - b).slice(0, 5) })}
                >
                  {offsetLabel(m)}
                </button>
              );
            })}
          </div>
          <label className="settings-row">
            <span className="settings-row-label">{t('Задачи без времени — в')}</span>
            <input className="settings-input" type="time" value={prefs.reminderDayTime} onChange={(e) => e.target.value && set({ reminderDayTime: e.target.value })} />
          </label>
          <label className="settings-row">
            <input type="checkbox" checked={prefs.pushReminders} onChange={(e) => set({ pushReminders: e.target.checked })} />
            <span>{t('Уведомления в браузере и на телефоне')}</span>
          </label>
          <label className="settings-row">
            <input type="checkbox" checked={prefs.emailReminders} onChange={(e) => set({ emailReminders: e.target.checked })} />
            <span>{t('Напоминания на email')}</span>
          </label>
          <label className="settings-row">
            <input type="checkbox" checked={prefs.digestEnabled} onChange={(e) => set({ digestEnabled: e.target.checked })} />
            <span>{t('Ежедневная сводка на email в')}</span>
            <input className="settings-input" type="time" aria-label={t('Время сводки')} value={prefs.digestTime} disabled={!prefs.digestEnabled} onChange={(e) => e.target.value && set({ digestTime: e.target.value })} />
          </label>
          {!session ? (
            <p className="settings-hint">{t('Напоминания отправляет сервер — войдите, чтобы их получать.')}</p>
          ) : !isPushSupported() || push === 'unsupported' ? (
            <p className="settings-hint">{t('Этот браузер не поддерживает уведомления.')}</p>
          ) : push === 'denied' ? (
            <p className="settings-hint">{t('Уведомления запрещены в настройках браузера для этого сайта.')}</p>
          ) : (
            <div className="settings-actions">
              <button type="button" className="settings-btn" disabled={push === 'busy'} onClick={() => void togglePush()}>
                {push === 'on' ? t('Отключить уведомления в этом браузере') : t('Включить уведомления в этом браузере')}
              </button>
              {push === 'on' && (
                <button
                  type="button"
                  className="settings-btn settings-btn--ghost"
                  onClick={() =>
                    void sendTestPush().then(
                      (n) => setMessage(n > 0 ? t('Тестовое уведомление отправлено') : t('Нет активных подписок')),
                      () => setMessage(t('Не удалось отправить')),
                    )
                  }
                >
                  {t('Проверить')}
                </button>
              )}
            </div>
          )}
          {message && <p className="settings-hint" role="status">{message}</p>}
        </Section>

        <Section title={t('Двухфакторная аутентификация')}>
          <SecuritySettings />
        </Section>

        <Section title={t('Экспорт данных')}>
          <div className="settings-actions">
            <button
              type="button"
              className="settings-btn"
              onClick={async () => {
                const data = session ? await exportAccount().catch(() => localExport()) : await localExport();
                download('taskly-export.json', JSON.stringify(data, null, 2), 'application/json');
              }}
            >
              {t('Скачать всё (JSON)')}
            </button>
            <button type="button" className="settings-btn settings-btn--ghost" onClick={async () => download('taskly-tasks.csv', await tasksCsv(), 'text/csv')}>
              {t('Задачи в CSV')}
            </button>
          </div>
        </Section>

        <Section title={t('Удаление аккаунта')} danger>
          <DeleteAccount />
        </Section>
      </div>
    </div>
  );
};

const DeleteAccount: React.FC = () => {
  const session = useStore(store, (s) => s.session);
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState('');
  const [confirmEmail, setConfirmEmail] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!session) {
    return (
      <button
        type="button"
        className="settings-btn settings-btn--danger"
        onClick={async () => {
          if (!window.confirm(t('Удалить все задачи с этого устройства?'))) return;
          await clearLocalData();
          await refreshData();
        }}
      >
        {t('Удалить данные с этого устройства')}
      </button>
    );
  }
  if (!open) {
    return (
      <>
        <p className="settings-hint">{t('Аккаунт и все задачи будут удалены на сервере и на этом устройстве. Это нельзя отменить — сначала скачайте экспорт.')}</p>
        <button type="button" className="settings-btn settings-btn--danger" onClick={() => setOpen(true)}>
          {t('Удалить аккаунт…')}
        </button>
      </>
    );
  }
  return (
    <form
      className="settings-delete"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        try {
          await deleteAccount({ password: password || undefined, confirmEmail: confirmEmail || undefined, totpCode: code || undefined });
          await clearLocalData();
          store.getState().setSession(null);
          store.getState().setCurrentView({ kind: 'system', view: 'inbox' });
          await refreshData();
        } catch (err) {
          setError(
            err instanceof ApiError
              ? err.code === 'totp_required'
                ? t('Введите код из приложения-аутентификатора')
                : err.code === 'password_invalid'
                  ? t('Неверный пароль')
                  : err.message
              : t('Сервер недоступен'),
          );
        } finally {
          setBusy(false);
        }
      }}
    >
      <input className="settings-input" type="password" autoComplete="current-password" aria-label={t('Пароль')} placeholder={t('Пароль')} value={password} onChange={(e) => setPassword(e.target.value)} />
      <input className="settings-input" type="email" aria-label={t('Email (для аккаунтов Google без пароля)')} placeholder={t('или email, если входите через Google')} value={confirmEmail} onChange={(e) => setConfirmEmail(e.target.value)} />
      <input className="settings-input" inputMode="numeric" maxLength={6} aria-label={t('Код 2FA, если включена')} placeholder={t('Код 2FA, если включена')} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
      {error && <p className="settings-error" role="alert">{error}</p>}
      <div className="settings-actions">
        <button type="submit" className="settings-btn settings-btn--danger" disabled={busy || (!password && !confirmEmail)}>
          {t('Удалить навсегда')}
        </button>
        <button type="button" className="settings-btn settings-btn--ghost" onClick={() => setOpen(false)}>
          {t('Отмена')}
        </button>
      </div>
    </form>
  );
};

export default SettingsPage;
