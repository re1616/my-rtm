// Taskly Web — "Done · Undo" toast (SPEC FR-6).
import React from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { dismissUndo, undoLast } from './undo';
import { t } from '../../i18n/i18n';

const UndoToast: React.FC = () => {
  const toast = useStore(store, (s) => s.toast);
  if (!toast) return null;
  return (
    <div className="undo-toast" role="status" aria-live="polite">
      <span>{toast.label}</span>
      <button type="button" className="undo-toast-btn" onClick={() => void undoLast()}>
        {t('Отменить')} <kbd>Ctrl+Z</kbd>
      </button>
      <button type="button" className="undo-toast-close" aria-label={t('Скрыть')} onClick={dismissUndo}>
        ×
      </button>
    </div>
  );
};

export default UndoToast;
