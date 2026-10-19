// Taskly Web — smart-list editor (WEB-F5): write the query or build it from conditions,
// see the problem highlighted at its position, preview the matching tasks, save under a name.
import React, { useMemo, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { checkQuery, runQuery } from '../query/smartQuery';
import { createSmartList } from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import { navigate } from '../sidebar/Sidebar';
import { msg, t } from '../../i18n/i18n';

type FieldId =
  | 'list' | 'tag' | 'priority' | 'due' | 'dueBefore' | 'dueWithin' | 'completed' | 'isRepeating'
  | 'hasSubtasks' | 'location' | 'addedWithin' | 'postponed' | 'timeEstimate' | 'text';

interface FieldDef {
  id: FieldId;
  label: string;
  /** Fixed choices; free text when absent. */
  options?: (ctx: { lists: string[]; tags: string[]; locations: string[] }) => { value: string; label: string }[];
  placeholder?: string;
}

const YES_NO = () => [
  { value: 'true', label: msg('да') },
  { value: 'false', label: msg('нет') },
];

const FIELDS: FieldDef[] = [
  { id: 'list', label: msg('Список'), options: (c) => c.lists.map((v) => ({ value: v, label: v })) },
  { id: 'tag', label: msg('Тег'), options: (c) => c.tags.map((v) => ({ value: v, label: '#' + v })) },
  { id: 'priority', label: msg('Приоритет'), options: () => ['1', '2', '3', 'none'].map((v) => ({ value: v, label: v === 'none' ? msg('нет') : v })) },
  {
    id: 'due',
    label: msg('Срок'),
    options: () => [
      { value: 'today', label: msg('сегодня') },
      { value: 'tomorrow', label: msg('завтра') },
      { value: 'overdue', label: msg('просрочено') },
      { value: 'this week', label: msg('на этой неделе') },
      { value: 'next week', label: msg('на следующей неделе') },
    ],
  },
  { id: 'dueBefore', label: msg('Срок до'), placeholder: 'tomorrow, 2026-12-31, 2 weeks' },
  { id: 'dueWithin', label: msg('Срок в пределах'), placeholder: '1 week, 3 days' },
  { id: 'completed', label: msg('Выполнена'), options: YES_NO },
  { id: 'isRepeating', label: msg('Повторяется'), options: YES_NO },
  { id: 'hasSubtasks', label: msg('Есть подзадачи'), options: YES_NO },
  { id: 'location', label: msg('Место'), options: (c) => c.locations.map((v) => ({ value: v, label: v })) },
  { id: 'addedWithin', label: msg('Добавлена за'), placeholder: '1 week, 30 days' },
  { id: 'postponed', label: msg('Отложена раз'), placeholder: '>2' },
  { id: 'timeEstimate', label: msg('Оценка'), placeholder: '<1 hour, >30 minutes' },
  { id: 'text', label: msg('Текст в названии'), placeholder: msg('слово') },
];

interface Condition {
  field: FieldId;
  value: string;
  not: boolean;
}

function quote(v: string): string {
  return /^[\w.<>=-]+$/u.test(v) && !/^(and|or|not)$/i.test(v) ? v : `"${v.replace(/"/g, '')}"`;
}

/** Conditions → query text in the shared language. */
export function buildQuery(conditions: Condition[], join: 'AND' | 'OR'): string {
  return conditions
    .filter((c) => c.value.trim())
    .map((c) => {
      const v = c.value.trim();
      const term = c.field === 'text' ? quote(v) : c.field === 'postponed' && /^[<>]=?\d+$/.test(v) ? `postponed:${v}` : `${c.field}:${quote(v)}`;
      return c.not ? `NOT ${term}` : term;
    })
    .join(` ${join} `);
}

const SmartListBuilder: React.FC = () => {
  const lists = useStore(store, (s) => s.lists);
  const tagNames = useStore(store, (s) => s.tagNames);
  const locations = useStore(store, (s) => s.locations);
  const allTasks = useStore(store, (s) => s.allTasks);

  const [query, setQuery] = useState('');
  const [name, setName] = useState('');
  const [conditions, setConditions] = useState<Condition[]>([{ field: 'tag', value: '', not: false }]);
  const [join, setJoin] = useState<'AND' | 'OR'>('AND');
  const [saveError, setSaveError] = useState<string | null>(null);

  const ctx = useMemo(
    () => ({
      lists: ['Inbox', ...lists.filter((l) => !l.isSystem && !l.isSmart).map((l) => l.name)],
      tags: tagNames,
      locations: locations.map((l) => l.name),
    }),
    [lists, tagNames, locations],
  );

  const problem = useMemo(() => (query.trim() ? checkQuery(query) : null), [query]);
  const matches = useMemo(() => {
    if (!query.trim() || problem) return [];
    try {
      return runQuery(query, allTasks, { lists, locations });
    } catch {
      return [];
    }
  }, [query, problem, allTasks, lists, locations]);

  function applyBuilder(next: Condition[], nextJoin = join) {
    setConditions(next);
    setQuery(buildQuery(next, nextJoin));
  }

  async function save() {
    setSaveError(null);
    if (!name.trim()) return setSaveError(t('Введите название'));
    if (!query.trim()) return setSaveError(t('Запрос пустой'));
    if (problem) return setSaveError(problem.message);
    const list = await createSmartList(name.trim(), query.trim());
    await refreshData();
    navigate({ kind: 'smart', listId: list.id });
  }

  const pos = problem?.position;

  return (
    <section className="smart-builder" aria-label={t('Умный список')}>
      <h2 className="smart-builder-title">{t('Новый умный список')}</h2>

      <label className="smart-builder-label" htmlFor="smart-query">
        {t('Запрос')}
      </label>
      <div className="smart-builder-query">
        <input
          id="smart-query"
          className={`smart-builder-input${problem ? ' smart-builder-input--error' : ''}`}
          value={query}
          spellCheck={false}
          autoComplete="off"
          aria-invalid={!!problem}
          aria-describedby="smart-query-status"
          placeholder={t('tag:работа AND (priority:1 OR due:today) AND NOT completed:true')}
          onChange={(e) => setQuery(e.target.value)}
        />
        {problem && pos !== undefined && pos >= 0 && (
          <div className="smart-builder-mark" aria-hidden="true">
            <span className="smart-builder-mark-ok">{query.slice(0, pos)}</span>
            <span className="smart-builder-mark-bad">{query.slice(pos, pos + 1) || ' '}</span>
          </div>
        )}
      </div>
      <p id="smart-query-status" className={problem ? 'smart-builder-error' : 'smart-builder-ok'} role={problem ? 'alert' : 'status'}>
        {problem ? t('Ошибка: {message}', { message: problem.message }) : query.trim() ? t('Подходит задач: {n}', { n: matches.length }) : t('Операторы AND, OR, NOT, скобки; поля — в конструкторе ниже.')}
      </p>

      <fieldset className="smart-builder-conditions">
        <legend>{t('Конструктор')}</legend>
        <div className="smart-builder-join" role="radiogroup" aria-label={t('Как объединять условия')}>
          {(['AND', 'OR'] as const).map((j) => (
            <label key={j}>
              <input type="radio" name="join" checked={join === j} onChange={() => { setJoin(j); applyBuilder(conditions, j); }} />
              {j === 'AND' ? t('все условия') : t('любое условие')}
            </label>
          ))}
        </div>
        {conditions.map((c, i) => {
          const def = FIELDS.find((f) => f.id === c.field)!;
          const opts = def.options?.(ctx);
          const update = (patch: Partial<Condition>) => applyBuilder(conditions.map((x, j) => (j === i ? { ...x, ...patch } : x)));
          return (
            <div key={i} className="smart-builder-row">
              <label className="smart-builder-not">
                <input type="checkbox" checked={c.not} onChange={(e) => update({ not: e.target.checked })} /> {t('не')}
              </label>
              <select aria-label={t('Поле условия {n}', { n: i + 1 })} value={c.field} onChange={(e) => update({ field: e.target.value as FieldId, value: '' })}>
                {FIELDS.map((f) => (
                  <option key={f.id} value={f.id}>
                    {t(f.label)}
                  </option>
                ))}
              </select>
              {opts ? (
                <select aria-label={t('Значение условия {n}', { n: i + 1 })} value={c.value} onChange={(e) => update({ value: e.target.value })}>
                  <option value="">—</option>
                  {opts.map((o) => (
                    <option key={o.value} value={o.value}>
                      {t(o.label)}
                    </option>
                  ))}
                </select>
              ) : (
                <input aria-label={t('Значение условия {n}', { n: i + 1 })} value={c.value} placeholder={def.placeholder ? t(def.placeholder) : undefined} onChange={(e) => update({ value: e.target.value })} />
              )}
              <button type="button" className="smart-builder-remove" aria-label={t('Убрать условие {n}', { n: i + 1 })} onClick={() => applyBuilder(conditions.filter((_, j) => j !== i))}>
                ×
              </button>
            </div>
          );
        })}
        <button type="button" className="smart-builder-add" onClick={() => setConditions([...conditions, { field: 'priority', value: '', not: false }])}>
          {t('+ Условие')}
        </button>
      </fieldset>

      {matches.length > 0 && (
        <div className="smart-builder-preview" aria-label={t('Предпросмотр')}>
          <h3>{t('Предпросмотр')}</h3>
          <ul>
            {matches.slice(0, 20).map((t) => (
              <li key={t.id}>{t.name}</li>
            ))}
          </ul>
          {matches.length > 20 && <p>{t('…и ещё {n}', { n: matches.length - 20 })}</p>}
        </div>
      )}

      <form
        className="smart-builder-save"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <input aria-label={t('Название умного списка')} placeholder={t('Название')} value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit" className="smart-builder-save-btn">
          {t('Сохранить умный список')}
        </button>
        {saveError && <span className="smart-builder-error" role="alert">{saveError}</span>}
      </form>
    </section>
  );
};

export default SmartListBuilder;
