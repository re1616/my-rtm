// SPEC 3.4: all UI strings live in catalogs (RU source, EN translation). This test keeps it so:
// every t()/msg() string has an English entry, no Russian text is left outside t()/msg(),
// and the catalog has no stale entries.
import { describe, it, expect, afterEach } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { en } from '../i18n/en';
import { plural, setLocale, t, TASKS } from '../i18n/i18n';

const SRC = join(__dirname, '..');
/** Data, not UI text: local names of the seeded system lists. */
const ALLOWED = new Set(['db/database.ts', 'i18n/en.ts', 'i18n/i18n.ts']);

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return name === '__tests__' ? [] : sources(p);
    return /\.(ts|tsx)$/.test(name) && !name.endsWith('.d.ts') ? [p] : [];
  });
}

const KEY = /\b(?:t|msg)\(\s*'((?:[^'\\]|\\.)*)'/g;
const CYR = /[А-Яа-яЁё]/;

function stripComments(code: string): string {
  return code
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => l.replace(/(^|[^:'"`])\/\/.*$/, '$1'))
    .join('\n');
}

const files = sources(SRC).map((p) => ({ path: relative(SRC, p).replace(/\\/g, '/'), code: readFileSync(p, 'utf8') }));
const keys = new Set(files.flatMap((f) => [...f.code.matchAll(KEY)].map((m) => m[1].replace(/\\'/g, "'"))));

afterEach(() => setLocale('ru'));

describe('localization catalogs (RU/EN)', () => {
  it('every string shown through t() or msg() has an English translation', () => {
    const missing = [...keys].filter((k) => CYR.test(k) && !(k in en));
    expect(missing).toEqual([]);
  });

  it('no Russian UI text outside t()/msg()', () => {
    const offenders: string[] = [];
    for (const f of files) {
      if (ALLOWED.has(f.path)) continue;
      // Data rather than UI text can be marked with an `i18n-ignore` comment on the line
      const code = stripComments(f.code.split('\n').map((l) => (l.includes('i18n-ignore') ? '' : l)).join('\n')).replace(KEY, '');
      code.split('\n').forEach((line, i) => {
        if (CYR.test(line)) offenders.push(`${f.path}:${i + 1}: ${line.trim()}`);
      });
    }
    expect(offenders).toEqual([]);
  });

  it('the catalog has no stale entries and keeps placeholders', () => {
    expect(Object.keys(en).filter((k) => !keys.has(k))).toEqual([]);
    for (const [ru, eng] of Object.entries(en)) {
      const ph = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
      expect(ph(eng), ru).toEqual(ph(ru));
    }
  });

  it('switches language and pluralizes by the language rules', () => {
    expect(t('Входящие')).toBe('Входящие');
    expect(plural(1, TASKS)).toBe('1 задача');
    expect(plural(3, TASKS)).toBe('3 задачи');
    expect(plural(11, TASKS)).toBe('11 задач');
    setLocale('en');
    expect(t('Входящие')).toBe('Inbox');
    expect(plural(1, TASKS)).toBe('1 task');
    expect(plural(3, TASKS)).toBe('3 tasks');
    expect(t('Выполнить «{name}»', { name: 'X' })).toBe('Complete “X”');
  });
});
