// Taskly Web — Smart Add Input Component
// Token highlighting via chip preview below the input
import React, { useState, useEffect } from 'react';
import { parseSmartAdd } from './parser';
import type { SmartAddResult } from '../../types';

interface SmartAddProps {
  onSubmit: (result: SmartAddResult) => void;
  placeholder?: string;
}

const SmartAddInput: React.FC<SmartAddProps> = ({ onSubmit, placeholder }) => {
  const [input, setInput] = useState('');
  const [parsed, setParsed] = useState<SmartAddResult | null>(null);
  const [isFocused, setIsFocused] = useState(false);

  useEffect(() => {
    if (input.trim()) {
      setParsed(parseSmartAdd(input));
    } else {
      setParsed(null);
    }
  }, [input]);

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
      <div className="smartadd-input-row">
        <span className="smartadd-prefix">+</span>
        <input
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