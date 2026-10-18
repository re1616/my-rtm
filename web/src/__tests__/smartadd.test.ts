// Smart Add in the web = the shared Kotlin/JS parser (no TS re-implementation).
// Expectations are taken from the shared corpus (SmartAddCorpusTest: today 2026-10-17,
// Europe/Moscow = UTC+03), so the web, server and Android behave identically.
import { describe, it, expect } from 'vitest';
import { parseSmartAdd, offsetString } from '../features/smartadd/parser';

const ctx = { today: '2026-10-17', timeZone: 'UTC+03' };
const parse = (input: string, extra: Partial<typeof ctx> & { listNames?: string[]; tagNames?: string[] } = {}) =>
  parseSmartAdd(input, { ...ctx, ...extra });

/** Local (UTC+03) calendar date of an ISO instant. */
const mskDate = (iso?: string) => (iso ? new Date(Date.parse(iso) + 3 * 3600_000).toISOString().slice(0, 10) : undefined);

describe('Smart Add (shared KMP parser)', () => {
  it('plain text has no tokens', () => {
    const r = parse('Buy milk');
    expect(r.name).toBe('Buy milk');
    expect(r.tokens).toHaveLength(0);
  });

  it.each([
    ['Купить молоко ^завтра', '2026-10-18'],
    ['Купить молоко ^через 3 дня', '2026-10-20'],
    ['Купить молоко ^пт', '2026-10-23'],
    ['Купить молоко ^25 дек', '2026-12-25'],
    ['Купить молоко ^через месяц', '2026-11-17'],
    ['Buy milk ^tomorrow', '2026-10-18'],
  ])('due date: %s → %s', (input, date) => {
    const r = parse(input);
    expect(r.name).toBe(input.split(' ^')[0]);
    expect(mskDate(r.dueDate)).toBe(date);
    expect(r.dueHasTime).toBe(false);
    expect(r.tokens.map((t) => t.type)).toEqual(['dueDate']);
  });

  it('date-only due is local midnight as an instant', () => {
    expect(parse('Купить молоко ^завтра').dueDate).toBe('2026-10-17T21:00:00Z');
  });

  it('due with time keeps the time', () => {
    const r = parse('Купить молоко ^завтра 17:00');
    expect(r.dueDate).toBe('2026-10-18T14:00:00Z');
    expect(r.dueHasTime).toBe(true);
  });

  it('start date (~)', () => {
    expect(mskDate(parse('Спортзал ~через 2 дня').startDate)).toBe('2026-10-19');
  });

  it.each([
    ['Задача !1', 1],
    ['Задача !2', 2],
    ['Задача !3', 3],
  ])('priority: %s', (input, p) => {
    const r = parse(input);
    expect(r.name).toBe('Задача');
    expect(r.priority).toBe(p);
  });

  it('first # is a list, the rest are tags', () => {
    const r = parse('Отчёт #Работа #срочно');
    expect(r.name).toBe('Отчёт');
    expect(r.listName).toBe('Работа');
    expect(r.tags).toEqual(['срочно']);
  });

  it('a known list wins over a tag with the same name', () => {
    const lists = ['Работа', 'Покупки'];
    const tags = ['работа', 'звонки'];
    expect(parse('Задача #работа', { listNames: lists, tagNames: tags })).toMatchObject({ listName: 'работа', tags: [] });
    expect(parse('Задача #звонки', { listNames: lists, tagNames: tags })).toMatchObject({ tags: ['звонки'] });
  });

  it('repeat every / after', () => {
    const every = parse('Совещание *каждую неделю');
    expect(every).toMatchObject({ name: 'Совещание', repeatMode: 'every' });
    expect(every.repeatRule).toMatch(/FREQ=WEEKLY/);

    const after = parse('Уборка *после 2 недель');
    expect(after).toMatchObject({ name: 'Уборка', repeatMode: 'after' });
    expect(after.repeatRule).toMatch(/FREQ=WEEKLY/);
    expect(after.repeatRule).toMatch(/INTERVAL=2/);

    expect(parse('Йога *каждый понедельник').repeatRule).toMatch(/BYDAY=MO/);
  });

  it('estimate, location, note', () => {
    expect(parse('Задача =1.5ч')).toMatchObject({ name: 'Задача', estimateMin: 90 });
    expect(parse('Встреча @офис')).toMatchObject({ name: 'Встреча', location: 'офис' });
    expect(parse('Позвонить маме // сказать про отпуск')).toMatchObject({
      name: 'Позвонить маме',
      note: 'сказать про отпуск',
    });
  });

  it('token positions point into the original string', () => {
    const input = 'Купить ^завтра молоко #дом';
    const r = parse(input);
    const due = r.tokens.find((t) => t.type === 'dueDate')!;
    expect(input.slice(due.start, due.end)).toBe('^завтра');
    expect(due.raw).toBe('^завтра');
    const list = r.tokens.find((t) => t.type === 'list')!;
    expect(list.raw).toBe('#дом');
  });

  it('empty input', () => {
    const r = parse('');
    expect(r.name).toBe('');
    expect(r.tokens).toHaveLength(0);
  });

  it('formats the browser offset for the fallback zone', () => {
    expect(offsetString(new Date())).toMatch(/^UTC[+-]\d{2}:\d{2}$/);
  });
});
