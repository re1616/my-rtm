// Taskly Web — Smart Add input (WEB-F1).
// Tokens recognized by the shared parser are highlighted while typing (overlay + chips),
// "#" suggests lists and tags, "@" suggests locations; ↑/↓ choose, Tab/Enter insert.
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from 'zustand';
import { parseSmartAdd } from './parser';
import type { SmartAddResult, SmartAddToken } from '../../types';
import store from '../../store/taskStore';

interface SmartAddProps {
  onSubmit: (result: SmartAddResult) => void | Promise<unknown>;
  placeholder?: string;
  /** Accessible name; also used by tests. */
  label?: string;
  /** Keep this input as the target of the "T" hotkey (the main list input). */
  primary?: boolean;
  autoFocus?: boolean;
}

const TOKEN_LABEL: Record<SmartAddToken['type'], string> = {
  text: 'текст',
  dueDate: 'срок',
  startDate: 'начало',
  priority: 'приоритет',
  list: 'список',
  tag: 'тег',
  repeat: 'повтор',
  location: 'место',
  estimate: 'оценка',
  contact: 'контакт',
  note: 'заметка',
};

function renderHighlighted(input: string, tokens: SmartAddToken[]): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  let last = 0;
  [...tokens]
    .sort((a, b) => a.start - b.start)
    .forEach((t, i) => {
      if (t.start < last) return;
      if (t.start > last) out.push(<span key={`p${i}`}>{input.slice(last, t.start)}</span>);
      out.push(
        <mark key={`t${i}`} className={`token-highlight token-highlight--${t.type}`}>
          {input.slice(t.start, t.end)}
        </mark>,
      );
      last = t.end;
    });
  if (last < input.length) out.push(<span key="end">{input.slice(last)}</span>);
  return out;
}

interface Suggestion {
  kind: 'list' | 'tag' | 'location';
  value: string;
}

/** The "#…" / "@…" word ending at the caret, if any. */
function wordAtCaret(input: string, caret: number): { sigil: '#' | '@'; prefix: string; start: number } | null {
  const before = input.slice(0, caret);
  const m = /(^|\s)([#@])([^\s#@]*)$/.exec(before);
  if (!m) return null;
  return { sigil: m[2] as '#' | '@', prefix: m[3], start: caret - m[3].length - 1 };
}

const SmartAddInput: React.FC<SmartAddProps> = ({ onSubmit, placeholder, label, primary, autoFocus }) => {
  const lists = useStore(store, (s) => s.lists);
  const tagNames = useStore(store, (s) => s.tagNames);
  const locations = useStore(store, (s) => s.locations);
  const listNames = useMemo(() => lists.filter((l) => !l.isSystem && !l.isSmart).map((l) => l.name), [lists]);

  const [input, setInput] = useState('');
  const [caret, setCaret] = useState(0);
  const [focused, setFocused] = useState(false);
  const [active, setActive] = useState(0);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const layerRef = useRef<HTMLDivElement>(null);

  const parsed = useMemo<SmartAddResult | null>(() => {
    if (!input.trim()) return null;
    try {
      return parseSmartAdd(input, { listNames, tagNames });
    } catch {
      return null;
    }
  }, [input, listNames, tagNames]);

  const word = focused ? wordAtCaret(input, caret) : null;
  const suggestions = useMemo<Suggestion[]>(() => {
    if (!word) return [];
    const p = word.prefix.toLowerCase();
    const match = (v: string) => v.toLowerCase().startsWith(p) && v.toLowerCase() !== p;
    if (word.sigil === '@') return locations.map((l) => l.name).filter(match).slice(0, 8).map((value) => ({ kind: 'location' as const, value }));
    const fromLists = listNames.filter(match).map((value) => ({ kind: 'list' as const, value }));
    const fromTags = tagNames.filter((t) => match(t) && !listNames.includes(t)).map((value) => ({ kind: 'tag' as const, value }));
    return [...fromLists, ...fromTags].slice(0, 8);
  }, [word, locations, listNames, tagNames]);

  useEffect(() => setActive(0), [suggestions.length, word?.start]);

  // Keep the highlight layer aligned with the scrolled input
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    const sync = () => {
      if (layerRef.current) layerRef.current.scrollLeft = el.scrollLeft;
    };
    el.addEventListener('scroll', sync);
    return () => el.removeEventListener('scroll', sync);
  }, []);

  function accept(s: Suggestion) {
    if (!word) return;
    const value = /\s/.test(s.value) ? s.value.replace(/\s+/g, '_') : s.value;
    const next = input.slice(0, word.start) + word.sigil + value + ' ' + input.slice(caret);
    const pos = word.start + 1 + value.length + 1;
    setInput(next);
    setCaret(pos);
    requestAnimationFrame(() => inputRef.current?.setSelectionRange(pos, pos));
  }

  async function submit() {
    if (busy) return;
    const result = parsed;
    if (!result || !result.name.trim()) return;
    setBusy(true);
    try {
      await onSubmit(result);
      setInput('');
      setCaret(0);
    } finally {
      setBusy(false);
      // Scenario 1: the field keeps the focus for the next task
      inputRef.current?.focus();
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (suggestions.length) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActive((a) => (a + 1) % suggestions.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((a) => (a - 1 + suggestions.length) % suggestions.length);
        return;
      }
      if (e.key === 'Tab' || e.key === 'Enter') {
        e.preventDefault();
        accept(suggestions[active]);
        return;
      }
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      void submit();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      if (input) setInput('');
      else inputRef.current?.blur();
    }
  }

  const listboxId = useMemo(() => `smartadd-suggest-${Math.random().toString(36).slice(2, 8)}`, []);

  return (
    <div className="smartadd-container">
      <div className={`smartadd-input-row ${focused ? 'smartadd-input-row--focused' : ''}`}>
        <span className="smartadd-prefix" aria-hidden="true">+</span>
        <div className="smartadd-input-wrapper">
          <div ref={layerRef} className="smartadd-highlight-layer" aria-hidden="true">
            {parsed ? renderHighlighted(input, parsed.tokens) : input}
          </div>
          <input
            ref={inputRef}
            type="text"
            className={`smartadd-input${primary ? ' smartadd-input--primary' : ''}`}
            aria-label={label ?? 'Новая задача'}
            role="combobox"
            aria-expanded={suggestions.length > 0}
            aria-controls={listboxId}
            aria-autocomplete="list"
            aria-activedescendant={suggestions.length ? `${listboxId}-${active}` : undefined}
            value={input}
            autoFocus={autoFocus}
            onChange={(e) => {
              setInput(e.target.value);
              setCaret(e.target.selectionStart ?? e.target.value.length);
            }}
            onSelect={(e) => setCaret((e.target as HTMLInputElement).selectionStart ?? 0)}
            onKeyDown={onKeyDown}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            placeholder={placeholder ?? 'Новая задача: ^завтра #список !1 *каждую неделю @место =30 мин'}
            autoComplete="off"
            spellCheck={false}
          />
        </div>
      </div>
      {suggestions.length > 0 && (
        <ul className="smartadd-suggestions" role="listbox" id={listboxId}>
          {suggestions.map((s, i) => (
            <li
              key={`${s.kind}-${s.value}`}
              id={`${listboxId}-${i}`}
              role="option"
              aria-selected={i === active}
              className={`smartadd-suggestion${i === active ? ' smartadd-suggestion--active' : ''}`}
              onMouseDown={(e) => {
                e.preventDefault();
                accept(s);
              }}
            >
              <span className="smartadd-suggestion-kind">{s.kind === 'list' ? 'список' : s.kind === 'tag' ? 'тег' : 'место'}</span>
              {s.value}
            </li>
          ))}
        </ul>
      )}
      {parsed && parsed.tokens.length > 0 && (
        <div className="smartadd-preview" aria-live="polite">
          {parsed.tokens.map((t, i) => (
            <span key={i} className={`smartadd-chip smartadd-chip--${t.type}`} title={TOKEN_LABEL[t.type]}>
              <span className="smartadd-chip-kind">{TOKEN_LABEL[t.type]}</span> {t.value}
            </span>
          ))}
        </div>
      )}
    </div>
  );
};

export default SmartAddInput;
