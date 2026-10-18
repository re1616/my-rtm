// Taskly Web — Smart List Builder (scaffold)
// Smart list constructor: query input, highlighting, save
import React, { useState } from 'react';

const SmartListBuilder: React.FC = () => {
  const [query, setQuery] = useState('');

  return (
    <div className="smart-list-builder">
      <div className="smart-list-builder-header">
        <h2 className="smart-list-builder-title">Умные списки</h2>
        <p className="smart-list-builder-desc">
          Создавайте динамические списки задач по фильтрам, тегам и срокам
        </p>
      </div>

      <div className="smart-list-builder-query-area">
        <label className="smart-list-builder-label">Запрос фильтрации</label>
        <input
          type="text"
          className="smart-list-builder-input"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder='Например: priority:1 & due:today & #work'
          autoComplete="off"
          spellCheck={false}
        />
        <div className="smart-list-builder-hint">
          Используйте: priority:N, due:date, #tag, list:name, completed:yes/no
        </div>
      </div>

      <div className="smart-list-builder-preview">
        <h3 className="smart-list-builder-section-title">Предпросмотр</h3>
        <div className="smart-list-builder-empty">
          {query
            ? 'Результаты фильтрации появятся здесь'
            : 'Введите запрос для предпросмотра'}
        </div>
      </div>

      <div className="smart-list-builder-actions">
        <button className="smart-list-builder-save-btn" disabled={!query.trim()}>
          Сохранить как умный список
        </button>
        <button className="smart-list-builder-cancel-btn">
          Отмена
        </button>
      </div>

      <div className="smart-list-builder-saved">
        <h3 className="smart-list-builder-section-title">Сохранённые списки</h3>
        <div className="smart-list-builder-empty">
          Сохранённые умные списки появятся здесь
        </div>
      </div>
    </div>
  );
};

export default SmartListBuilder;