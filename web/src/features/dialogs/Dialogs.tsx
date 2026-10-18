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

const TITLES: Record<DialogKind, string> = {
  shortcuts: 'Горячие клавиши',
  move: 'Переместить в список',
  tags: 'Добавить теги',
  untag: 'Убрать теги',
  due: 'Срок',
  rename: 'Переименовать',
  repeat: 'Повтор',
  'smart-save': 'Сохранить как умный список',
};

const Dialogs: React.FC = () => {
  const dialog = useStore(store, (s) => s.dialog);
  if (!dialog) return null;
  const close = () => store.getState().setDialog(null);
  return (
    <div className="dialog-backdrop" onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title" onKeyDown={(e) => e.key === 'Escape' && close()}>
        <h3 id="dialog-title" className="dialog-title">{TITLES[dialog]}</h3>
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
              <td>{s.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="dialog-actions">
        <button ref={ref} className="dialog-btn" onClick={onClose}>Закрыть</button>
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
    const all = [{ id: INBOX_ID, name: 'Входящие' }, ...lists.filter((l) => !l.isSystem && !l.isSmart).map((l) => ({ id: l.id, name: l.name }))];
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
        aria-label="Найти список"
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
      <ul className="dialog-options" role="listbox" aria-label="Списки">
        {options.map((o, i) => (
          <li key={o.id} role="option" aria-selected={i === active} className={`dialog-option${i === active ? ' dialog-option--active' : ''}`} onMouseDown={() => void choose(o.id)}>
            {o.name}
          </li>
        ))}
        {options.length === 0 && <li className="dialog-option dialog-option--empty">Нет такого списка</li>}
      </ul>
    </>
  );
};

const HINTS: Partial<Record<DialogKind, string>> = {
  due: 'Например: завтра 17:00, пт, 25 дек, через 3 дня. Пусто — без срока.',
  repeat: 'Например: каждую неделю, every 2 days, after 1 week. Пусто — без повтора.',
  tags: 'Через пробел или запятую: звонки, дом',
  untag: 'Через пробел или запятую',
  'smart-save': 'Название, под которым запрос появится в сайдбаре',
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
      return d?.dueDate ? new Date(d.dueDate).toLocaleString(undefined, d.dueHasTime ? undefined : { dateStyle: 'medium' }) : 'не распознано';
    }
    if (kind === 'repeat' && value.trim()) return parseRepeat(value)?.repeatRule ?? 'не распознано';
    return null;
  }, [kind, value]);

  async function submit() {
    const words = value.split(/[\s,]+/).filter(Boolean);
    switch (kind) {
      case 'due':
        if (!(await setDueTargets(value))) return setError('Не удалось распознать дату');
        break;
      case 'repeat':
        if (!(await setRepeatTargets(value))) return setError('Не удалось распознать повтор');
        break;
      case 'tags':
        await addTagsTargets(words);
        break;
      case 'untag':
        await removeTagsTargets(words);
        break;
      case 'rename': {
        const id = targetIds()[0];
        if (id) await renameTask(id, value);
        break;
      }
      case 'smart-save': {
        const view = store.getState().currentView;
        if (view.kind !== 'search' || !value.trim()) return setError('Введите название');
        const problem = checkQuery(view.query);
        if (problem) return setError(problem.message);
        const list = await createSmartList(value.trim(), view.query);
        await refreshData();
        store.getState().setCurrentView({ kind: 'smart', listId: list.id });
        break;
      }
    }
    onClose();
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
        aria-label={TITLES[kind]}
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
      {HINTS[kind] && <p className="dialog-hint">{HINTS[kind]}</p>}
      {preview && <p className="dialog-preview" aria-live="polite">→ {preview}</p>}
      {error && <p className="dialog-error" role="alert">{error}</p>}
      <div className="dialog-actions">
        <button type="button" className="dialog-btn dialog-btn--ghost" onClick={onClose}>Отмена</button>
        <button type="submit" className="dialog-btn">Готово</button>
      </div>
    </form>
  );
};

export default Dialogs;
