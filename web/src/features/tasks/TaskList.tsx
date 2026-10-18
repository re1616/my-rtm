// Taskly Web — Task List View
import React from 'react';
import TaskRow from './TaskRow';
import SmartAddInput from '../smartadd/SmartAddInput';
import type { Task, SmartAddResult, AppView } from '../../types';
import {
  getTasksByList,
  createTask,
  completeTask,
  uncompleteTask,
  softDeleteTask,
  findListByName,
} from '../../entities/taskRepository';
import { triggerSync } from '../sync/SyncService';
import store from '../../store/taskStore';

interface TaskListProps {
  view: AppView;
  tasks: Task[];
  isOnline: boolean;
}

async function refreshCurrentView(): Promise<void> {
  const storeState = store.getState();
  const refreshed = await getTasksByList(
    storeState.currentView.kind === 'system'
      ? `sys-${storeState.currentView.view}`
      : storeState.currentView.listId,
    {
      showCompleted: storeState.showCompleted,
      sortBy: storeState.sortBy,
      sortAsc: storeState.sortAsc,
    },
  );
  store.getState().setTasks(refreshed);
}

async function handleSmartAdd(result: SmartAddResult): Promise<void> {
  // "#Name" resolves to an existing list (the parser gives lists priority over tags);
  // otherwise the task goes to the list that is currently open, or the Inbox.
  const view = store.getState().currentView;
  const namedList = result.listName ? await findListByName(result.listName) : null;
  const listId = namedList?.id ?? (view.kind === 'list' ? view.listId : undefined);
  await createTask({
    name: result.name,
    listId,
    dueHasTime: result.dueHasTime,
    priority: result.priority as 1 | 2 | 3 | null,
    dueDate: result.dueDate,
    tags: result.tags,
    repeatRule: result.repeatRule,
    repeatMode: result.repeatMode,
    estimateMin: result.estimateMin,
    note: result.note,
  });
  await refreshCurrentView();
  void triggerSync();
}

const TaskList: React.FC<TaskListProps> = ({ view, tasks, isOnline }) => {
  const viewName = getViewName(view);

  return (
    <div className="task-list">
      <div className="task-list-header">
        <h2 className="task-list-title">{viewName}</h2>
        <span className="task-list-count">{tasks.length}</span>
      </div>

      <SmartAddInput
        onSubmit={(result) => {
          handleSmartAdd(result);
        }}
        placeholder={
          isOnline
            ? '\u041D\u043E\u0432\u0430\u044F \u0437\u0430\u0434\u0430\u0447\u0430: ^\u0441\u0440\u043E\u043A #\u0441\u043F\u0438\u0441\u043E\u043A !\u043F\u0440\u0438\u043E\u0440\u0438\u0442\u0435\u0442\u2026'
            : '\u0417\u0430\u0434\u0430\u0447\u0430 (\u043E\u0444\u043B\u0430\u0439\u043D): ^\u0441\u0440\u043E\u043A #\u0441\u043F\u0438\u0441\u043E\u043A'
        }
      />

      {!isOnline && (
        <div className="offline-banner">
          \u041D\u0435\u0442 \u043F\u043E\u0434\u043A\u043B\u044E\u0447\u0435\u043D\u0438\u044F \u043A \u0441\u0435\u0440\u0432\u0435\u0440\u0443. \u0418\u0437\u043C\u0435\u043D\u0435\u043D\u0438\u044F \u0441\u043E\u0445\u0440\u0430\u043D\u044F\u0442\u0441\u044F \u043B\u043E\u043A\u0430\u043B\u044C\u043D\u043E.
        </div>
      )}

      {tasks.length === 0 ? (
        <div className="task-list-empty">
          <div className="task-list-empty-icon">✓</div>
          <p>\u0412\u0441\u0435 \u0437\u0430\u0434\u0430\u0447\u0438 \u0440\u0430\u0437\u043E\u0431\u0440\u0430\u043D\u044B</p>
          <p className="task-list-empty-hint">
            \u0418\u0441\u043F\u043E\u043B\u044C\u0437\u0443\u0439\u0442\u0435 \u043F\u043E\u043B\u0435 \u0432\u0432\u043E\u0434\u0430 \u0432\u044B\u0448\u0435 \u0434\u043B\u044F \u0431\u044B\u0441\u0442\u0440\u043E\u0433\u043E \u0434\u043E\u0431\u0430\u0432\u043B\u0435\u043D\u0438\u044F
          </p>
        </div>
      ) : (
        <div className="task-list-rows">
          {tasks.map((task) => (
            <TaskRow
              key={task.id}
              task={task}
              isSelected={store.getState().selectedTaskIds.includes(task.id)}
              onToggleSelect={(id) => store.getState().toggleTaskSelection(id)}
              onComplete={async (id) => {
                if (task.completedAt) {
                  await uncompleteTask(id);
                } else {
                  await completeTask(id);
                }
                await refreshCurrentView();
                void triggerSync();
              }}
              onDelete={async (id) => {
                await softDeleteTask(id);
                await refreshCurrentView();
                void triggerSync();
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
};

function getViewName(view: AppView): string {
  if (view.kind === 'system') {
    const names: Record<string, string> = {
      inbox: '\u0412\u0445\u043E\u0434\u044F\u0449\u0438\u0435',
      today: '\u0421\u0435\u0433\u043E\u0434\u043D\u044F',
      overdue: '\u041F\u0440\u043E\u0441\u0440\u043E\u0447\u0435\u043D\u043E',
      week: '\u041D\u0435\u0434\u0435\u043B\u044F',
      all: '\u0412\u0441\u0435 \u0437\u0430\u0434\u0430\u0447\u0438',
      completed: '\u0412\u044B\u043F\u043E\u043B\u043D\u0435\u043D\u043D\u044B\u0435',
    };
    return names[view.view] || view.view;
  }
  if (view.kind === 'list') return '\u0421\u043F\u0438\u0441\u043E\u043A';
  return '\u0423\u043C\u043D\u044B\u0439 \u0441\u043F\u0438\u0441\u043E\u043A';
}

export default TaskList;