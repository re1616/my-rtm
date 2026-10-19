// Taskly Web — top bar (SPEC 4.1): global search with the query language, sync state.
import React, { useEffect, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { navigate } from '../sidebar/Sidebar';
import { checkQuery } from '../query/smartQuery';
import { requestSync } from '../sync/SyncService';
import { t } from '../../i18n/i18n';

const SYNC_TEXT: Record<string, string> = {
  idle: 'Синхронизировано',
  offline: 'Офлайн',
  unauthenticated: 'Без синхронизации',
  error: 'Ошибка синхронизации',
};

const TopBar: React.FC = () => {
  const view = useStore(store, (s) => s.currentView);
  const syncState = useStore(store, (s) => s.syncState);
  const session = useStore(store, (s) => s.session);
  const [text, setText] = useState(view.kind === 'search' ? view.query : '');

  useEffect(() => {
    if (view.kind !== 'search') setText('');
  }, [view]);

  const problem = text.trim() ? checkQuery(text) : null;

  return (
    <header className="topbar">
      <form
        className="topbar-search"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          if (text.trim()) navigate({ kind: 'search', query: text.trim() });
        }}
      >
        <input
          className={`topbar-search-input${problem ? ' topbar-search-input--error' : ''}`}
          type="search"
          aria-label={t('Поиск задач')}
          placeholder={t('Поиск: слово или запрос (tag:работа AND due:today) — клавиша /')}
          value={text}
          aria-invalid={!!problem}
          title={problem?.message}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              e.preventDefault();
              (e.target as HTMLInputElement).blur();
            }
          }}
        />
      </form>
      {view.kind === 'search' && (
        <button type="button" className="topbar-btn" onClick={() => store.getState().setDialog('smart-save')}>
          {t('Сохранить как умный список')}
        </button>
      )}
      {session && (
        <button type="button" className={`topbar-sync topbar-sync--${syncState}`} onClick={() => requestSync()} title={t('Синхронизировать сейчас')}>
          {t(SYNC_TEXT[syncState] ?? syncState)}
        </button>
      )}
    </header>
  );
};

export default TopBar;
