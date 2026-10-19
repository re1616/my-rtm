// WEB-F9 (settings), WEB-F11 (onboarding), WEB-F8 (notification actions) through the UI and
// the public client functions.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, within, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '@testing-library/jest-dom/vitest';
import App from '../App';
import store from '../store/taskStore';
import { getDb } from '../db/database';
import { createTask, getTaskById, getLists } from '../entities/taskRepository';
import { dismissUndo } from '../features/undo/undo';
import { setLocale } from '../i18n/i18n';
import { handleNotificationAction } from '../features/push/PushService';
import { loadPrefs, syncPrefs, updatePrefs } from '../features/settings/prefs';
import { setSession } from '../api/client';

async function reset() {
  const db = getDb();
  await Promise.all([db.tasks, db.lists, db.tags, db.syncQueue, db.settings, db.notes, db.attachments, db.locations].map((t) => t.clear()));
  store.setState({
    currentView: { kind: 'system', view: 'inbox' }, tab: 'open', cursorId: null, openTaskId: null,
    selectedTaskIds: [], dialog: null, toast: null, session: null, prefs: null,
  });
}

async function renderApp() {
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('navigation');
  return user;
}

beforeEach(async () => {
  dismissUndo();
  setLocale('ru');
  document.documentElement.removeAttribute('data-theme');
  await reset();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('onboarding (WEB-F11)', () => {
  // jsdom reports an English browser; these scenarios are in Russian
  beforeEach(async () => {
    await getDb().settings.put({ key: 'prefs', value: JSON.stringify({ locale: 'ru' }) });
  });

  it('opens on the first launch, creates the chosen lists and is not shown again', async () => {
    const user = await renderApp();
    expect(await screen.findByRole('heading', { name: 'Добро пожаловать в Taskly' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Далее' }));
    await user.click(screen.getByRole('checkbox', { name: 'Покупки' }));
    await user.click(screen.getByRole('checkbox', { name: 'Дом' })); // was on by default → off
    await user.click(screen.getByRole('button', { name: 'Далее' }));
    // Step 3 has the interactive cheat sheet
    expect(screen.getByRole('region', { name: 'Шпаргалка Smart Add' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Начать работу' }));

    expect(await screen.findByRole('heading', { name: 'Входящие' })).toBeInTheDocument();
    const names = (await getLists()).filter((l) => !l.isSystem).map((l) => l.name).sort();
    expect(names).toEqual(['Покупки', 'Работа']);
    expect(store.getState().prefs?.onboarded).toBe(true);

    cleanup();
    await renderApp();
    expect(await screen.findByRole('heading', { name: 'Входящие' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Добро пожаловать в Taskly' })).toBeNull();
  });

  it('is skipped when the user already has tasks', async () => {
    await createTask({ name: 'Уже есть' });
    await renderApp();
    expect(await screen.findByText('Уже есть')).toBeInTheDocument();
  });
});

describe('settings (WEB-F9)', () => {
  beforeEach(async () => {
    await getDb().settings.put({ key: 'prefs', value: JSON.stringify({ onboarded: true, locale: 'ru' }) });
  });

  it('switches the whole interface to English and back, and the theme', async () => {
    const user = await renderApp();
    await user.click(screen.getByRole('button', { name: 'Настройки' }));
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Язык интерфейса' }), 'en');
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Navigation' });
    expect(within(nav).getByRole('button', { name: /^Inbox/ })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe('en');

    await user.click(screen.getByRole('radio', { name: 'Dark' }));
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');

    // Survives a restart: stored on the device
    cleanup();
    setLocale('ru');
    await renderApp();
    expect(await screen.findByRole('navigation', { name: 'Navigation' })).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');

    await user.click(screen.getByRole('button', { name: 'Settings' }));
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Language' }), 'ru');
    expect(await screen.findByRole('heading', { name: 'Настройки' })).toBeInTheDocument();
  });

  it('reminder defaults are chosen with chips and stored', async () => {
    const user = await renderApp();
    await user.click(screen.getByRole('button', { name: 'Настройки' }));
    const group = await screen.findByRole('group', { name: 'Напоминать' });
    expect(within(group).getByRole('button', { name: 'за 15 мин' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(group).getByRole('button', { name: 'за 1 ч' }));
    await user.click(within(group).getByRole('button', { name: 'за 15 мин' }));
    await waitFor(async () => {
      const row = await getDb().settings.get('prefs');
      expect(JSON.parse(row!.value).reminderOffsets).toEqual([60]);
    });
    await user.click(screen.getByRole('checkbox', { name: /Ежедневная сводка/ }));
    expect(screen.getByLabelText('Время сводки')).toBeEnabled();
  });

  it('without an account, "delete data from this device" wipes local tasks', async () => {
    await createTask({ name: 'Личное' });
    vi.stubGlobal('confirm', () => true);
    const user = await renderApp();
    await user.click(screen.getByRole('button', { name: 'Настройки' }));
    await user.click(await screen.findByRole('button', { name: 'Удалить данные с этого устройства' }));
    await waitFor(async () => expect(await getDb().tasks.count()).toBe(0));
  });
});

describe('settings sync with the account', () => {
  it('a change made offline is sent on the next sync; the server value wins otherwise', async () => {
    await setSession({ userId: 'u1', accessToken: 'a', refreshToken: 'r' });
    await loadPrefs();
    const calls: Array<{ method: string; body: unknown }> = [];
    let online = false;
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      if (!online) throw new TypeError('Failed to fetch');
      calls.push({ method: init.method ?? 'GET', body: init.body ? JSON.parse(String(init.body)) : undefined });
      const base = { timeZone: 'Europe/Moscow', locale: 'ru', theme: 'system', reminderOffsets: [15], reminderDayTime: '08:00', pushReminders: true, emailReminders: false, digestEnabled: false, digestTime: '07:00', weekStart: 1, onboarded: true };
      const body = init.method === 'PATCH' ? { ...base, ...(JSON.parse(String(init.body)) as object) } : { ...base, digestEnabled: true };
      expect(String(url)).toContain('/v1/account/settings');
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });

    await updatePrefs({ reminderDayTime: '09:30' }); // offline
    expect(store.getState().prefs?.reminderDayTime).toBe('09:30');
    online = true;
    await syncPrefs();
    expect(calls[0]).toMatchObject({ method: 'PATCH', body: expect.objectContaining({ reminderDayTime: '09:30' }) });

    await syncPrefs(); // nothing pending: take the server's settings
    expect(calls[1].method).toBe('GET');
    expect(store.getState().prefs?.digestEnabled).toBe(true);
  });
});

describe('notification buttons (WEB-F8, SPEC FR-17)', () => {
  it('"Выполнить" completes the task, "Отложить на 1 ч" moves the due time', async () => {
    const a = await createTask({ name: 'A' });
    const b = await createTask({ name: 'B', dueDate: new Date(Date.now() - 60_000).toISOString(), dueHasTime: true });
    await handleNotificationAction(a.id, 'complete');
    expect((await getTaskById(a.id))?.completedAt).toBeTruthy();

    const before = Date.now();
    await handleNotificationAction(b.id, 'snooze');
    const after = await getTaskById(b.id);
    expect(new Date(after!.dueDate!).getTime()).toBeGreaterThanOrEqual(before + 3_600_000 - 1000);
    expect(after?.dueHasTime).toBe(true);
    expect(after?.postponedCount).toBe(1);

    await handleNotificationAction(b.id, 'open');
    expect(store.getState().openTaskId).toBe(b.id);
  });
});
