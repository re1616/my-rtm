// Taskly Web — Zustand Store
//
// One source of truth for the UI: every live task is loaded once (allTasks) and each view is
// derived from it in memory (features/app/view.ts), so a change made anywhere — Smart Add,
// the card, a hotkey, another tab, the server — shows up everywhere after one refreshData().
import { create } from 'zustand';
import type { Task, TaskList, TaskLocation, AppView, SortField, Session } from '../types';
import type { SyncState, SyncRejection } from '../features/sync/SyncService';

export type ListTab = 'open' | 'completed';

/** Modal prompts opened by hotkeys and the mass-actions bar. */
export type DialogKind = 'shortcuts' | 'move' | 'tags' | 'untag' | 'due' | 'rename' | 'repeat' | 'smart-save';

export interface UndoToast {
  id: number;
  label: string;
}

interface AppStore {
  // Data
  /** Every live (non-deleted) task, all lists. */
  allTasks: Task[];
  /** Tasks of the current view (kept for components and tests that read it directly). */
  tasks: Task[];
  lists: TaskList[];
  tagNames: string[];
  locations: TaskLocation[];
  /** Number of notes / attachments per task id (row icons). */
  noteCounts: Record<string, number>;
  attachmentCounts: Record<string, number>;

  // Navigation & selection
  currentView: AppView;
  tab: ListTab;
  /** Keyboard pointer (J/K) — the task actions apply to when nothing is selected. */
  cursorId: string | null;
  /** Task shown in the right-hand card. */
  openTaskId: string | null;
  selectedTaskIds: string[];
  dialog: DialogKind | null;
  toast: UndoToast | null;

  // Status
  isOnline: boolean;
  isSyncing: boolean;
  showCompleted: boolean;
  sortBy: SortField;
  sortAsc: boolean;
  syncState: SyncState;
  session: Session | null;
  /** Local changes the server refused (rolled back), newest first. */
  rejections: SyncRejection[];
  /** Tasks loaded by the first-sync snapshot so far; null when no snapshot is running. */
  snapshotLoaded: number | null;
  /** A new app version is installed and waits for the user's go-ahead (WEB-S8). */
  updateReady: boolean;

  // Actions
  setData: (d: {
    allTasks: Task[];
    lists: TaskList[];
    tagNames: string[];
    locations: TaskLocation[];
    noteCounts?: Record<string, number>;
    attachmentCounts?: Record<string, number>;
  }) => void;
  setTasks: (tasks: Task[]) => void;
  setLists: (lists: TaskList[]) => void;
  setCurrentView: (view: AppView) => void;
  setTab: (tab: ListTab) => void;
  setCursor: (id: string | null) => void;
  openTask: (id: string | null) => void;
  setSelectedTaskIds: (ids: string[]) => void;
  toggleTaskSelection: (id: string) => void;
  clearSelection: () => void;
  setDialog: (d: DialogKind | null) => void;
  setToast: (t: UndoToast | null) => void;
  setIsOnline: (online: boolean) => void;
  setIsSyncing: (syncing: boolean) => void;
  setShowCompleted: (show: boolean) => void;
  setSortBy: (field: SortField) => void;
  toggleSortAsc: () => void;
  setSyncState: (s: SyncState) => void;
  setSession: (s: Session | null) => void;
  setRejections: (r: SyncRejection[]) => void;
  setSnapshotLoaded: (n: number | null) => void;
  setUpdateReady: (ready: boolean) => void;
}

const store = create<AppStore>()((set) => ({
  allTasks: [],
  tasks: [],
  lists: [],
  tagNames: [],
  locations: [],
  noteCounts: {},
  attachmentCounts: {},
  currentView: { kind: 'system', view: 'inbox' },
  tab: 'open',
  cursorId: null,
  openTaskId: null,
  selectedTaskIds: [],
  dialog: null,
  toast: null,
  isOnline: typeof navigator !== 'undefined' && navigator.onLine !== undefined ? navigator.onLine : true,
  isSyncing: false,
  showCompleted: false,
  sortBy: 'createdAt',
  sortAsc: true,
  syncState: 'idle',
  session: null,
  rejections: [],
  snapshotLoaded: null,
  updateReady: false,

  setData: ({ allTasks, lists, tagNames, locations, noteCounts, attachmentCounts }) =>
    set({ allTasks, lists, tagNames, locations, noteCounts: noteCounts ?? {}, attachmentCounts: attachmentCounts ?? {} }),
  setTasks: (tasks) => set({ tasks }),
  setLists: (lists) => set({ lists }),
  setCurrentView: (view) => set({ currentView: view, selectedTaskIds: [], cursorId: null, tab: 'open' }),
  setTab: (tab) => set({ tab, selectedTaskIds: [], cursorId: null }),
  setCursor: (cursorId) => set({ cursorId }),
  openTask: (openTaskId) => set({ openTaskId }),
  setSelectedTaskIds: (ids) => set({ selectedTaskIds: ids }),
  toggleTaskSelection: (id) =>
    set((state) => ({
      selectedTaskIds: state.selectedTaskIds.includes(id)
        ? state.selectedTaskIds.filter((tid) => tid !== id)
        : [...state.selectedTaskIds, id],
    })),
  clearSelection: () => set({ selectedTaskIds: [] }),
  setDialog: (dialog) => set({ dialog }),
  setToast: (toast) => set({ toast }),
  setIsOnline: (online) => set({ isOnline: online }),
  setIsSyncing: (syncing) => set({ isSyncing: syncing }),
  setShowCompleted: (show) => set({ showCompleted: show }),
  setSortBy: (field) => set({ sortBy: field }),
  toggleSortAsc: () => set((state) => ({ sortAsc: !state.sortAsc })),
  setSyncState: (syncState) => set({ syncState }),
  setSession: (session) => set({ session }),
  setRejections: (rejections) => set({ rejections }),
  setSnapshotLoaded: (snapshotLoaded) => set({ snapshotLoaded }),
  setUpdateReady: (updateReady) => set({ updateReady }),
}));

export default store;
export type AppStoreType = typeof store;
