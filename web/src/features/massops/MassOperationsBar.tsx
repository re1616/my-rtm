// Taskly Web — Mass Operations Bar (scaffold)
// Shown when tasks are selected: complete, delete, move, tag, priority bulk actions
import React from 'react';

interface MassOperationsBarProps {
  selectedCount: number;
  onComplete: () => void;
  onDelete: () => void;
  onMove: () => void;
  onSetPriority: (priority: number) => void;
  onAddTag: (tag: string) => void;
  onClearSelection: () => void;
}

const MassOperationsBar: React.FC<MassOperationsBarProps> = ({
  selectedCount,
  onComplete,
  onDelete,
  onMove,
  onSetPriority,
  onAddTag,
  onClearSelection,
}) => {
  if (selectedCount === 0) return null;

  return (
    <div className="massops-bar">
      <div className="massops-bar-info">
        <span className="massops-bar-count">
          Выбрано: <strong>{selectedCount}</strong>
        </span>
      </div>

      <div className="massops-bar-actions">
        <button
          className="massops-btn massops-btn--complete"
          onClick={onComplete}
          title="Завершить выбранные"
        >
          ✅ Завершить
        </button>

        <button
          className="massops-btn massops-btn--delete"
          onClick={onDelete}
          title="Удалить выбранные"
        >
          🗑 Удалить
        </button>

        <button
          className="massops-btn massops-btn--move"
          onClick={onMove}
          title="Переместить в список"
        >
          📂 Переместить
        </button>

        <div className="massops-btn-group">
          <span className="massops-btn-group-label">Приоритет:</span>
          <button
            className="massops-btn massops-btn--priority"
            onClick={() => onSetPriority(1)}
          >
            !1
          </button>
          <button
            className="massops-btn massops-btn--priority"
            onClick={() => onSetPriority(2)}
          >
            !2
          </button>
          <button
            className="massops-btn massops-btn--priority"
            onClick={() => onSetPriority(3)}
          >
            !3
          </button>
        </div>

        <button
          className="massops-btn massops-btn--tag"
          onClick={() => onAddTag('')}
          title="Добавить тег"
        >
          🏷 Тег
        </button>
      </div>

      <button
        className="massops-bar-close"
        onClick={onClearSelection}
        title="Снять выделение"
      >
        &times;
      </button>
    </div>
  );
};

export default MassOperationsBar;