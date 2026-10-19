// Taskly Web — user preferences (WEB-F9): language, theme, time zone, reminder defaults.
//
// Stored locally (IndexedDB) so they work offline and before sign-in; when signed in they are
// the account's settings on the server (shared with Android and used by the reminder
// scheduler). A change made offline is sent on the next sync.
import { getDb } from '../../db/database';
import { getAccountSettings, getSession, patchAccountSettings, type AccountSettings, type ApiDeps } from '../../api/client';
import { detectLocale, setLocale } from '../../i18n/i18n';
import store from '../../store/taskStore';

export type Prefs = AccountSettings;

const KEY = 'prefs';
const DIRTY_KEY = 'prefsDirty';

export function defaultPrefs(): Prefs {
  return {
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    locale: detectLocale(),
    theme: 'system',
    reminderOffsets: [15],
    reminderDayTime: '08:00',
    pushReminders: true,
    emailReminders: false,
    digestEnabled: false,
    digestTime: '07:00',
    weekStart: 1,
    onboarded: false,
  };
}

export async function loadPrefs(): Promise<Prefs> {
  const row = await getDb().settings.get(KEY);
  let p = defaultPrefs();
  if (row) {
    try {
      p = { ...p, ...(JSON.parse(row.value) as Partial<Prefs>) };
    } catch {
      /* keep defaults */
    }
  }
  applyPrefs(p);
  return p;
}

/** Language and theme take effect at once; everything goes into the store. */
export function applyPrefs(p: Prefs): void {
  setLocale(p.locale);
  if (typeof document !== 'undefined') {
    const root = document.documentElement;
    if (p.theme === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', p.theme);
  }
  store.getState().setPrefs(p);
}

async function save(p: Prefs): Promise<void> {
  await getDb().settings.put({ key: KEY, value: JSON.stringify(p) });
}

/** Changes preferences locally and on the server (later, if offline). */
export async function updatePrefs(patch: Partial<Prefs>, deps: ApiDeps = {}): Promise<Prefs> {
  const next = { ...store.getState().prefs, ...patch } as Prefs;
  await save(next);
  applyPrefs(next);
  if (await getSession()) {
    try {
      const server = await patchAccountSettings(patch, deps);
      await getDb().settings.delete(DIRTY_KEY);
      return adopt(server);
    } catch {
      await getDb().settings.put({ key: DIRTY_KEY, value: '1' });
    }
  }
  return next;
}

async function adopt(server: AccountSettings): Promise<Prefs> {
  const merged = { ...store.getState().prefs, ...server } as Prefs;
  await save(merged);
  applyPrefs(merged);
  return merged;
}

/**
 * After sign-in and on each sync: local changes made offline go to the server first,
 * otherwise the server's settings (possibly changed on another device) are taken.
 */
export async function syncPrefs(deps: ApiDeps = {}): Promise<void> {
  if (!(await getSession())) return;
  const dirty = await getDb().settings.get(DIRTY_KEY);
  if (dirty) {
    const p = store.getState().prefs;
    if (!p) return;
    const server = await patchAccountSettings(p, deps);
    await getDb().settings.delete(DIRTY_KEY);
    await adopt(server);
  } else {
    await adopt(await getAccountSettings(deps));
  }
}
