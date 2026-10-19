// Taskly Web — what the centre pane shows for the current view.
import type { AppView, Task, TaskList, TaskLocation } from '../../types';
import { inView, sortTasks, INBOX_ID } from '../../entities/taskRepository';
import { checkQuery, runQuery } from '../query/smartQuery';
import type { ListTab } from '../../store/taskStore';
import { msg, t } from '../../i18n/i18n';

export const SYSTEM_VIEWS: { view: Extract<AppView, { kind: 'system' }>['view']; label: string; icon: string }[] = [
  { view: 'inbox', label: msg('Входящие'), icon: '📥' },
  { view: 'today', label: msg('Сегодня'), icon: '☀️' },
  { view: 'tomorrow', label: msg('Завтра'), icon: '🌅' },
  { view: 'week', label: msg('На этой неделе'), icon: '📆' },
  { view: 'overdue', label: msg('Просрочено'), icon: '⏰' },
  { view: 'all', label: msg('Все задачи'), icon: '📋' },
  { view: 'completed', label: msg('Выполненные'), icon: '✅' },
];

export interface ViewInput {
  view: AppView;
  tab: ListTab;
  allTasks: Task[];
  lists: TaskList[];
  locations: TaskLocation[];
  sortBy: string;
  sortAsc: boolean;
}

export interface ViewResult {
  tasks: Task[];
  /** Problem with a smart list / search query, shown instead of results. */
  error?: string;
}

/** Query of a smart list or search view, if any. */
export function viewQuery(view: AppView, lists: TaskList[]): string | null {
  if (view.kind === 'search') return view.query;
  if (view.kind === 'smart') return lists.find((l) => l.id === view.listId)?.filterQuery ?? '';
  return null;
}

export function computeView(input: ViewInput): ViewResult {
  const { view, tab, allTasks, lists, locations } = input;
  const completedTab = tab === 'completed';
  const byTab = (t: Task) => (completedTab ? !!t.completedAt : !t.completedAt);

  let tasks: Task[];
  const query = viewQuery(view, lists);
  if (query !== null) {
    if (!query.trim()) return { tasks: [] };
    const problem = checkQuery(query);
    if (problem) return { tasks: [], error: problem.message };
    try {
      tasks = runQuery(query, allTasks, { lists, locations }).filter(byTab);
    } catch (e) {
      return { tasks: [], error: e instanceof Error ? e.message : String(e) };
    }
    // Search and smart lists show every match, subtasks included
    return { tasks: sortTasks(tasks, input.sortBy, input.sortAsc) };
  }

  switch (view.kind) {
    case 'system': {
      const listId = 'sys-' + view.view;
      tasks = allTasks.filter((t) => inView(t, listId));
      if (view.view !== 'completed') tasks = tasks.filter(byTab);
      break;
    }
    case 'list':
      tasks = allTasks.filter((t) => t.listId === view.listId).filter(byTab);
      break;
    case 'tag':
      tasks = allTasks.filter((t) => t.tags.includes(view.tag)).filter(byTab);
      break;
    case 'location':
      tasks = allTasks.filter((t) => t.locationId === view.locationId).filter(byTab);
      break;
    default:
      tasks = [];
  }
  // Subtasks live in their parent's card unless the parent is not in this view
  const ids = new Set(tasks.map((t) => t.id));
  tasks = tasks.filter((t) => !t.parentId || !ids.has(t.parentId));
  return { tasks: sortTasks(tasks, input.sortBy, input.sortAsc) };
}

export function viewTitle(view: AppView, lists: TaskList[], locations: TaskLocation[]): string {
  switch (view.kind) {
    case 'system':
      return t(SYSTEM_VIEWS.find((s) => s.view === view.view)?.label ?? view.view);
    case 'list':
    case 'smart':
      return lists.find((l) => l.id === view.listId)?.name ?? t('Список');
    case 'tag':
      return '#' + view.tag;
    case 'location':
      return '@' + (locations.find((l) => l.id === view.locationId)?.name ?? '');
    case 'search':
      return t('Поиск');
    case 'settings':
      return t('Настройки');
    case 'onboarding':
      return t('Знакомство');
    case 'smart-lists':
      return t('Умные списки');
    default:
      return t('Задачи');
  }
}

/** List a task added in this view goes to (Smart Add without "#list"). */
export function defaultListFor(view: AppView): string {
  return view.kind === 'list' ? view.listId : INBOX_ID;
}

export function sameView(a: AppView, b: AppView): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
