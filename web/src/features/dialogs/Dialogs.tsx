// Taskly Web — prompts opened by hotkeys and the mass-actions bar: move to list (M), due (D),
// tags (S), repeat (F), rename (R), save search as smart list, and the shortcut help (?).
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from 'zustand';
import store, { type DialogKind } from '../../store/taskStore';
import {
  addTagsTargets,
  moveTargets,
  parseDue,
  parseRepeat,
  removeTagsTargets,
  renameTask,
  setDueTargets,
  setRepeatTargets,
  targetIds,
} from '../tasks/actions';
import { INBOX_ID, createSmartList } from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import { SHORTCUTS } from '../hotkeys/Hotkeys';
import { checkQuery } from '../query/smartQuery';
import { intlLocale, msg, t } from '../../i18n/i18n';

const TITLES: Record<DialogKind, string> = {
  shortcuts: msg('Горячие клавиши'),
  move: msg('Переместить в список'),
  tags: msg('Добавить теги'),
  untag: msg('Убрать теги'),
  due: msg('Срок'),
  rename: msg('Переименовать'),
  repeat: msg('Повтор'),
  'smart-save': msg('Сохранить как умный список'),
};

const Dialogs: React.FC = () => {
  const dialog = useStore(store, (s) => s.dialog);
  if (!dialog) return null;
  const close = () => store.getState().setDialog(null);
  return (
    <div className="dialog-backdrop" onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title" onKeyDown={(e) => e.key === 'Escape' && close()}>
        <h3 id="dialog-title" className="dialog-title">{t(TITLES[dialog])}</h3>
        {dialog === 'shortcuts' ? <ShortcutHelp onClose={close} /> : dialog === 'move' ? <MovePicker onClose={close} /> : <TextPrompt kind={dialog} onClose={close} />}
      </div>
    </div>
  );
};

const ShortcutHelp: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => ref.current?.focus(), []);
  return (
    <>
      <table className="shortcut-table">
        <tbody>
          {SHORTCUTS.map((s) => (
            <tr key={s.keys}>
              <td>
                <kbd>{s.keys}</kbd>
              </td>
              <td>{t(s.description)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="dialog-actions">
        <button ref={ref} className="dialog-btn" onClick={onClose}>{t('Закрыть')}</button>
      </div>
    </>
  );
};

/** Type-ahead list picker: ↑/↓ and Enter, or click. */
const MovePicker: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const lists = useStore(store, (s) => s.lists);
  const [filter, setFilter] = useState('');
  const [active, setActive] = useState(0);
  const options = useMemo(() => {
    const all = [{ id: INBOX_ID, name: t('Входящие') }, ...lists.filter((l) => !l.isSystem && !l.isSmart).map((l) => ({ id: l.id, name: l.name }))];
    const f = filter.trim().toLowerCase();
    return f ? all.filter((o) => o.name.toLowerCase().includes(f)) : all;
  }, [lists, filter]);

  async function choose(id: string) {
    onClose();
    await moveTargets(id);
  }

  return (
    <>
      <input
        className="dialog-input"
        aria-label={t('Найти список')}
        autoFocus
        value={filter}
        onChange={(e) => {
          setFilter(e.target.value);
          setActive(0);
        }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setActive((a) => Math.min(a + 1, options.length - 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActive((a) => Math.max(a - 1, 0));
          } else if (e.key === 'Enter' && options[active]) {
            e.preventDefault();
            void choose(options[active].id);
          }
        }}
      />
      <ul className="dialog-options" role="listbox" aria-label={t('Списки')}>
        {options.map((o, i) => (
          <li key={o.id} role="option" aria-selected={i === active} className={`dialog-option${i === active ? ' dialog-option--active' : ''}`} onMouseDown={() => void choose(o.id)}>
            {o.name}
          </li>
        ))}
        {options.length === 0 && <li className="dialog-option dialog-option--empty">{t('Нет такого списка')}</li>}
      </ul>
    </>
  );
};

const HINTS: Partial<Record<DialogKind, string>> = {
  due: msg('Например: завтра 17:00, пт, 25 дек, через 3 дня. Пусто — без срока.'),
  repeat: msg('Например: каждую неделю, every 2 days, after 1 week. Пусто — без повтора.'),
  tags: msg('Через пробел или запятую: звонки, дом'),
  untag: msg('Через пробел или запятую'),
  'smart-save': msg('Название, под которым запрос появится в сайдбаре'),
};

const TextPrompt: React.FC<{ kind: DialogKind; onClose: () => void }> = ({ kind, onClose }) => {
  const initial = useMemo(() => {
    const s = store.getState();
    const first = s.allTasks.find((t) => t.id === targetIds()[0]);
    if (kind === 'rename') return first?.name ?? '';
    return '';
  }, [kind]);
  const [value, setValue] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const tagNames = useStore(store, (s) => s.tagNames);

  const preview = useMemo(() => {
    if (kind === 'due' && value.trim()) {
      const d = parseDue(value);
      return d?.dueDate ? new Date(d.dueDate).toLocaleString(intlLocale(), d.dueHasTime ? undefined : { dateStyle: 'medium' }) : t('не распознано');
    }
    if (kind === 'repeat' && value.trim()) return parseRepeat(value)?.repeatRule ?? t('не распознано');
    return null;
  }, [kind, value]);

  // Validation is synchronous; the dialog closes at once and the change is applied after,
  // so the next key press already goes to the list.
  async function submit() {
    const words = value.split(/[\s,]+/).filter(Boolean);
    const ids = targetIds();
    switch (kind) {
      case 'due':
        if (!parseDue(value)) return setError(t('Не удалось распознать дату'));
        onClose();
        await setDueTargets(value, ids);
        return;
      case 'repeat':
        if (!parseRepeat(value)) return setError(t('Не удалось распознать повтор'));
        onClose();
        await setRepeatTargets(value, ids);
        return;
      case 'tags':
        onClose();
        await addTagsTargets(words, ids);
        return;
      case 'untag':
        onClose();
        await removeTagsTargets(words, ids);
        return;
      case 'rename':
        onClose();
        if (ids[0]) await renameTask(ids[0], value);
        return;
      case 'smart-save': {
        const view = store.getState().currentView;
        if (view.kind !== 'search' || !value.trim()) return setError(t('Введите название'));
        const problem = checkQuery(view.query);
        if (problem) return setError(problem.message);
        onClose();
        const list = await createSmartList(value.trim(), view.query);
        await refreshData();
        store.getState().setCurrentView({ kind: 'smart', listId: list.id });
        return;
      }
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <input
        className="dialog-input"
        aria-label={t(TITLES[kind])}
        autoFocus
        list={kind === 'tags' || kind === 'untag' ? 'dialog-tag-options' : undefined}
        value={value}
        onChange={(e) => {
          setValue(e.target.value);
          setError(null);
        }}
      />
      {(kind === 'tags' || kind === 'untag') && (
        <datalist id="dialog-tag-options">
          {tagNames.map((t) => (
            <option key={t} value={t} />
          ))}
        </datalist>
      )}
      {HINTS[kind] && <p className="dialog-hint">{t(HINTS[kind]!)}</p>}
      {preview && <p className="dialog-preview" aria-live="polite">→ {preview}</p>}
      {error && <p className="dialog-error" role="alert">{error}</p>}
      <div className="dialog-actions">
        <button type="button" className="dialog-btn dialog-btn--ghost" onClick={onClose}>{t('Отмена')}</button>
        <button type="submit" className="dialog-btn">{t('Готово')}</button>
      </div>
    </form>
  );
};

export default Dialogs;
