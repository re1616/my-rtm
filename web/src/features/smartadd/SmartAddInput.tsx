// Taskly Web — Smart Add Input Component
// Inline token highlighting + chip preview + autocomplete dropdown for lists/tags/locations
import React, { useState, useEffect, useRef } from 'react';
import { parseSmartAdd } from './parser';
import type { SmartAddResult, SmartAddToken } from '../../types';
import store from '../../store/taskStore';

interface SmartAddProps {
  onSubmit: (result: SmartAddResult) => void;
  placeholder?: string;
}

// Token prefix to CSS class mapping for inline coloring
const TOKEN_CLASSES: Record<string, string> = {
  '^': 'highlight-due',
  '~': 'highlight-start',
  '!': 'highlight-priority',
  '#': 'highlight-tag',
  '*': 'highlight-repeat',
  '@': 'highlight-location',
  '=': 'highlight-estimate',
  '+': 'highlight-contact',
};

// Autocomplete suggestions by token type
interface AutoCompleteItem {
  type: string;
  label: string;
  value: string;
}

/**
 * Render input text with inline token highlighting spans.
 */
function renderHighlightedText(input: string, tokens: SmartAddToken[]): React.ReactNode[] {
  if (!tokens.length || !input) {
    return [<span key="plain">{input}</span>];
  }

  const result: React.ReactNode[] = [];
  let lastEnd = 0;

  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    // Plain text before this token
    if (t.start > lastEnd) {
      result.push(
        <span key={`plain-${lastEnd}`}>
          {input.substring(lastEnd, t.start)}
        </span>,
      );
    }
    // Highlighted token
    const prefix = t.raw[0];
    const cls = TOKEN_CLASSES[prefix] || 'highlight-default';
    result.push(
      <span key={`tok-${i}`} className={`token-highlight ${cls}`}>
        {t.raw}
      </span>,
    );
    lastEnd = t.end;
  }

  // Remaining text after last token
  if (lastEnd < input.length) {
    result.push(
      <span key={`plain-end`}>
        {input.substring(lastEnd)}
      </span>,
    );
  }

  return result;
}

const SmartAddInput: React.FC<SmartAddProps> = ({ onSubmit, placeholder }) => {
  const [input, setInput] = useState('');
  const [parsed, setParsed] = useState<SmartAddResult | null>(null);
  const [isFocused, setIsFocused] = useState(false);
  const [autoCompleteItems, setAutoCompleteItems] = useState<AutoCompleteItem[]>([]);
  const [autoCompletePrefix, setAutoCompletePrefix] = useState('');
  const [selectedSuggestion, setSelectedSuggestion] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const highlightRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (input.trim()) {
      const r = parseSmartAdd(input);
      setParsed(r);
      // Check for autocomplete triggers
      computeAutocomplete(input, r);
    } else {
      setParsed(null);
      setAutoCompleteItems([]);
    }
  }, [input]);

  function computeAutocomplete(text: string, result: SmartAddResult) {
    // Find where the cursor is — look at the last unfinished token
    const lastChar = text[text.length - 1];
    if (!lastChar) {
      setAutoCompleteItems([]);
      return;
    }

    const lists = store.getState().lists;
    const tasksArr = store.getState().tasks;

    // Check if we're typing after a prefix character
    const triggerPrefixes: Record<string, string> = { '#': 'list', '@': 'location' };
    const triggerChar = triggerPrefixes[lastChar];
    if (triggerChar) {
      const prefix = '';
      setAutoCompletePrefix(prefix);

      const suggestions: AutoCompleteItem[] = [];
      if (lastChar === '#') {
        // List names
        lists.filter((l) => !l.isSystem && !l.archived).forEach((l) => {
          suggestions.push({ type: 'list', label: `#${l.name}`, value: l.name });
        });
        // Tag names from tasks
        const tagSet = new Set(tasksArr.flatMap((t) => t.tags));
        tagSet.forEach((tag) => {
          suggestions.push({ type: 'tag', label: `#${tag}`, value: tag });
        });
      } else if (lastChar === '@') {
        // Locations: extract from notes metadata
        tasksArr
          .filter((t) => t.notes && t.notes.includes('@loc:'))
          .forEach((t) => {
            const m = t.notes!.match(/@loc:(\S+)/);
            if (m) {
              suggestions.push({ type: 'location', label: `@${m[1]}`, value: m[1] });
            }
          });
        // Common locations
        ['home', 'офис', 'work', 'shop', 'gym'].forEach((loc) => {
          if (!suggestions.some((s) => s.value === loc)) {
            suggestions.push({ type: 'location', label: `@${loc}`, value: loc });
          }
        });
      }

      setAutoCompleteItems(suggestions);
      setSelectedSuggestion(0);
      return;
    }

    // If we typed text after a prefix, filter suggestions
    const atIdx = text.lastIndexOf('@');
    if (atIdx >= 0 && atIdx === text.length - 1) return; // just typed @
    if (atIdx >= 0 && text.slice(atIdx).startsWith('@')) {
      const partial = text.slice(atIdx + 1);
      setAutoCompletePrefix(partial);
      const tasks = store.getState().tasks;
      const locSet = new Set(
        tasks
          .filter((t) => t.notes && t.notes.includes('@loc:'))
          .map((t) => {
            const m = t.notes!.match(/@loc:(\S+)/);
            return m ? m[1] : '';
          })
          .filter(Boolean),
      );
      const common = ['home', 'офис', 'work', 'shop', 'gym'];
      common.forEach((l) => locSet.add(l));
      const matches = [...locSet].filter((l) => l.startsWith(partial));
      setAutoCompleteItems(matches.map((m) => ({ type: 'location', label: `@${m}`, value: m })));
      setSelectedSuggestion(0);
      return;
    }

    const hashIdx = text.lastIndexOf('#');
    if (hashIdx >= 0 && text.slice(hashIdx).startsWith('#')) {
      const partial = text.slice(hashIdx + 1);
      setAutoCompletePrefix(partial);
      const lists = store.getState().lists;
      const listMatches = lists.filter((l) => !l.isSystem && !l.archived && l.name.startsWith(partial));
      const allTags = new Set(store.getState().tasks.flatMap((t) => t.tags));
      const tagMatches = [...allTags].filter((t) => t.startsWith(partial));
      const suggestions: AutoCompleteItem[] = [];
      listMatches.forEach((l) => suggestions.push({ type: 'list', label: `#${l.name}`, value: l.name }));
      tagMatches.forEach((t) => {
        if (!suggestions.some((s) => s.value === t)) {
          suggestions.push({ type: 'tag', label: `#${t}`, value: t });
        }
      });
      setAutoCompleteItems(suggestions);
      setSelectedSuggestion(0);
      return;
    }

    setAutoCompleteItems([]);
  }

  // Sync scroll position between highlight layer and input
  useEffect(() => {
    const inputEl = inputRef.current;
    const highlightEl = highlightRef.current;
    if (!inputEl || !highlightEl) return;

    function syncScroll() {
      if (highlightEl) {
        highlightEl.scrollLeft = inputEl!.scrollLeft;
      }
    }

    inputEl.addEventListener('scroll', syncScroll);
    return () => inputEl.removeEventListener('scroll', syncScroll);
  }, []);

  function handleSubmit() {
    const trimmed = input.trim();
    if (!trimmed) return;
    const result = parsed || parseSmartAdd(trimmed);
    if (!result.name && result.tokens.length === 0) return;
    onSubmit(result);
    setInput('');
    setParsed(null);
    setAutoCompleteItems([]);
  }

  function acceptSuggestion(item: AutoCompleteItem) {
    // Replace the partial prefix with the suggestion
    const atIdx = input.lastIndexOf('@');
    const hashIdx = input.lastIndexOf('#');
    const triggerIdx = Math.max(atIdx, hashIdx);
    if (triggerIdx >= 0) {
      const newInput = input.slice(0, triggerIdx + 1) + item.value + ' ';
      setInput(newInput);
    }
    setAutoCompleteItems([]);
    inputRef.current?.focus();
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (autoCompleteItems.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedSuggestion(Math.min(selectedSuggestion + 1, autoCompleteItems.length - 1));
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedSuggestion(Math.max(selectedSuggestion - 1, 0));
        return;
      }
      if (e.key === 'Tab' || e.key === 'Enter') {
        if (autoCompleteItems[selectedSuggestion]) {
          e.preventDefault();
          acceptSuggestion(autoCompleteItems[selectedSuggestion]);
          return;
        }
      }
      if (e.key === 'Escape') {
        setAutoCompleteItems([]);
      }
    }

    if (e.key === 'Enter' && autoCompleteItems.length === 0) {
      e.preventDefault();
      handleSubmit();
    }
    if (e.key === 'Escape' && autoCompleteItems.length === 0) {
      setInput('');
      setParsed(null);
      (e.target as HTMLInputElement).blur();
      setIsFocused(false);
    }
  }

  return (
    <div className="smartadd-container">
      <div className={`smartadd-input-row ${isFocused ? 'smartadd-input-row--focused' : ''}`}>
        <span className="smartadd-prefix">+</span>
        <div className="smartadd-input-wrapper">
          {/* Highlight layer (behind transparent input) */}
          <div
            ref={highlightRef}
            className="smartadd-highlight-layer"
            aria-hidden="true"
          >
            {parsed && input.trim()
              ? renderHighlightedText(input, parsed.tokens)
              : <span>{input}</span>}
          </div>
          {/* Transparent input on top */}
          <input
            ref={inputRef}
            type="text"
            className="smartadd-input"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            onFocus={() => setIsFocused(true)}
            onBlur={() => {
              // Delay hiding autocomplete so clicks register
              setTimeout(() => {
                setIsFocused(false);
                setAutoCompleteItems([]);
              }, 200);
            }}
            placeholder={placeholder || 'Новая задача: ^срок #список !приоритет\u2026'}
            autoComplete="off"
            spellCheck={false}
            role="combobox"
            aria-expanded={autoCompleteItems.length > 0 ? 'true' : 'false'}
          />
        </div>
        {/* Autocomplete dropdown */}
        {isFocused && autoCompleteItems.length > 0 && (
          <div className="smartadd-autocomplete">
            {autoCompleteItems.map((item, i) => (
              <div
                key={`${item.type}-${item.value}`}
                className={`smartadd-autocomplete-item ${i === selectedSuggestion ? 'smartadd-autocomplete-item--active' : ''}`}
                onClick={() => acceptSuggestion(item)}
                onMouseEnter={() => setSelectedSuggestion(i)}
              >
                <span className={`smartadd-autocomplete-type smartadd-autocomplete-type--${item.type}`}>
                  {item.type === 'list' ? '#' : item.type === 'tag' ? '#' : '@'}
                </span>
                <span className="smartadd-autocomplete-label">{item.label}</span>
              </div>
            ))}
          </div>
        )}
      </div>
      {isFocused && parsed && input.trim() && (
        <div className="smartadd-preview">
          <div className="smartadd-chips">
            {parsed.tokens.map((t, i) => (
              <span
                key={i}
                className={`smartadd-chip smartadd-chip--${t.type}`}
              >
                {t.raw}
              </span>
            ))}
          </div>
          {parsed.name && (
            <span className="smartadd-preview-name">
              {parsed.name}
            </span>
          )}
        </div>
      )}
    </div>
  );
};

export default SmartAddInput;