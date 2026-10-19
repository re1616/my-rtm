// Taskly Web — actions on the selected tasks (SPEC FR-4): complete, postpone, priority, due,
// list, tags, delete. Replaces the sort bar while something is selected; every action can be
// undone for 10 s.
import React from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { completeTargets, deleteTargets, postponeTargets, setPriorityTargets } from '../tasks/actions';
import { t } from '../../i18n/i18n';

const MassOperationsBar: React.FC = () => {
  const count = useStore(store, (s) => s.selectedTaskIds.length);
  if (count === 0) return null;
  const open = (d: Parameters<ReturnType<typeof store.getState>['setDialog']>[0]) => store.getState().setDialog(d);

  return (
    <div className="massops-bar" role="toolbar" aria-label={t('Действия с выбранными задачами')}>
      <span className="massops-bar-count" aria-live="polite">
        {t('Выбрано:')} <strong>{count}</strong>
      </span>
      <button className="massops-btn" onClick={() => void completeTargets()} title="C">{t('Выполнить')}</button>
      <button className="massops-btn" onClick={() => void postponeTargets()} title="P">{t('Отложить')}</button>
      <span className="massops-group" role="group" aria-label={t('Приоритет')}>
        {([1, 2, 3] as const).map((p) => (
          <button key={p} className={`massops-btn massops-btn--p${p}`} onClick={() => void setPriorityTargets(p)} title={String(p)} aria-label={t('Приоритет {n}', { n: p })}>
            !{p}
          </button>
        ))}
        <button className="massops-btn" onClick={() => void setPriorityTargets(null)} title="4" aria-label={t('Без приоритета')}>—</button>
      </span>
      <button className="massops-btn" onClick={() => open('due')} title="D">{t('Срок')}</button>
      <button className="massops-btn" onClick={() => open('move')} title="M">{t('В список')}</button>
      <button className="massops-btn" onClick={() => open('tags')} title="S">{t('+ Теги')}</button>
      <button className="massops-btn" onClick={() => open('untag')}>{t('− Теги')}</button>
      <button className="massops-btn massops-btn--danger" onClick={() => void deleteTargets()} title="Delete">{t('Удалить')}</button>
      <button className="massops-bar-close" onClick={() => store.getState().clearSelection()} aria-label={t('Снять выделение (N)')}>×</button>
    </div>
  );
};

export default MassOperationsBar;
