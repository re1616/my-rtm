// Taskly Web — actions on the selected tasks (SPEC FR-4): complete, postpone, priority, due,
// list, tags, delete. Replaces the sort bar while something is selected; every action can be
// undone for 10 s.
import React from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { completeTargets, deleteTargets, postponeTargets, setPriorityTargets } from '../tasks/actions';

const MassOperationsBar: React.FC = () => {
  const count = useStore(store, (s) => s.selectedTaskIds.length);
  if (count === 0) return null;
  const open = (d: Parameters<ReturnType<typeof store.getState>['setDialog']>[0]) => store.getState().setDialog(d);

  return (
    <div className="massops-bar" role="toolbar" aria-label="Действия с выбранными задачами">
      <span className="massops-bar-count" aria-live="polite">
        Выбрано: <strong>{count}</strong>
      </span>
      <button className="massops-btn" onClick={() => void completeTargets()} title="C">Выполнить</button>
      <button className="massops-btn" onClick={() => void postponeTargets()} title="P">Отложить</button>
      <span className="massops-group" role="group" aria-label="Приоритет">
        {([1, 2, 3] as const).map((p) => (
          <button key={p} className={`massops-btn massops-btn--p${p}`} onClick={() => void setPriorityTargets(p)} title={String(p)} aria-label={`Приоритет ${p}`}>
            !{p}
          </button>
        ))}
        <button className="massops-btn" onClick={() => void setPriorityTargets(null)} title="4" aria-label="Без приоритета">—</button>
      </span>
      <button className="massops-btn" onClick={() => open('due')} title="D">Срок</button>
      <button className="massops-btn" onClick={() => open('move')} title="M">В список</button>
      <button className="massops-btn" onClick={() => open('tags')} title="S">+ Теги</button>
      <button className="massops-btn" onClick={() => open('untag')}>− Теги</button>
      <button className="massops-btn massops-btn--danger" onClick={() => void deleteTargets()} title="Delete">Удалить</button>
      <button className="massops-bar-close" onClick={() => store.getState().clearSelection()} aria-label="Снять выделение (N)">×</button>
    </div>
  );
};

export default MassOperationsBar;
