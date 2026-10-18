// Taskly Web — Smart Add Input Component
// Inline token highlighting + chip preview below the input
import React, { useState, useEffect, useRef } from 'react';
import { parseSmartAdd } from './parser';
import type { SmartAddResult, SmartAddToken } from '../../types';

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

/**
 * Render input text with inline token highlighting spans.
 * Splits text into plain segments and token-highlighted spans.
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
  const inputRef = useRef<HTMLInputElement>(null);
  const highlightRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (input.trim()) {
      setParsed(parseSmartAdd(input));
    } else {
      setParsed(null);
    }
  }, [input]);

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
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSubmit();
    }
    if (e.key === 'Escape') {
      setInput('');
      setParsed(null);
      (e.target as HTMLInputElement).blur();
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
            onBlur={() => setIsFocused(false)}
            placeholder={placeholder || 'Новая задача: ^срок #список !приоритет\u2026'}
            autoComplete="off"
            spellCheck={false}
          />
        </div>
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