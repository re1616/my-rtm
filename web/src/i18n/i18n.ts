// Taskly Web — localization (SPEC 3.4: RU/EN, all strings in catalogs, dates by locale).
//
// Russian is the source language: t('Входящие') returns the Russian text or its English
// translation from the catalog (gettext style, so the code stays readable). Placeholders are
// written as {name}; plurals use Intl.PluralRules (Russian one/few/many, English one/other).
import { en } from './en';

export type Locale = 'ru' | 'en';

let current: Locale = 'ru';
const listeners = new Set<(l: Locale) => void>();

export function getLocale(): Locale {
  return current;
}

export function setLocale(locale: Locale): void {
  if (locale !== 'ru' && locale !== 'en') return;
  if (locale === current) return;
  current = locale;
  if (typeof document !== 'undefined') document.documentElement.lang = locale;
  for (const l of listeners) l(locale);
}

export function onLocaleChange(fn: (l: Locale) => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** Browser language → supported locale (first run). */
export function detectLocale(): Locale {
  const langs = typeof navigator !== 'undefined' ? navigator.languages ?? [navigator.language] : [];
  return langs.some((l) => l?.toLowerCase().startsWith('ru')) ? 'ru' : langs.length ? 'en' : 'ru';
}

function fill(text: string, params?: Record<string, string | number>): string {
  if (!params) return text;
  return text.replace(/\{(\w+)\}/g, (m, k: string) => (k in params ? String(params[k]) : m));
}

/** Translates a Russian source string. */
export function t(ru: string, params?: Record<string, string | number>): string {
  const text = current === 'en' ? en[ru] ?? ru : ru;
  return fill(text, params);
}

export interface PluralForms {
  /** Russian: 1 задача, 2 задачи, 5 задач */
  ru: [one: string, few: string, many: string];
  en: [one: string, other: string];
}

/** "{n} задача/задачи/задач" by the current language's plural rules. */
export function plural(n: number, forms: PluralForms): string {
  const rule = new Intl.PluralRules(current).select(n);
  const text =
    current === 'en'
      ? rule === 'one' ? forms.en[0] : forms.en[1]
      : rule === 'one' ? forms.ru[0] : rule === 'few' ? forms.ru[1] : forms.ru[2];
  return fill(text, { n });
}

export const TASKS: PluralForms = { ru: ['{n} задача', '{n} задачи', '{n} задач'], en: ['{n} task', '{n} tasks'] };

/** BCP 47 tag for Intl date/number formatting. */
export function intlLocale(): string {
  return current === 'en' ? 'en-GB' : 'ru-RU';
}

export function formatDate(d: Date, opts: Intl.DateTimeFormatOptions = { dateStyle: 'medium' }): string {
  return d.toLocaleDateString(intlLocale(), opts);
}

export function formatDateTime(d: Date, opts: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' }): string {
  return d.toLocaleString(intlLocale(), opts);
}

/** Marks a Russian string in a constant table for the catalog; translate it with t() when shown. */
export const msg = (ru: string): string => ru;
