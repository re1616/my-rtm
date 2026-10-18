// Taskly Web — Zustand Store
import { create } from 'zustand';
import type { Task, TaskList, AppView, SortField, Session } from '../types';
import type { SyncState, SyncRejection } from '../features/sync/SyncService';

interface AppStore {
  // State
  tasks: Task[];
  lists: TaskList[];
  currentView: AppView;
  selectedTaskIds: string[];
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
  setTasks: (tasks: Task[]) => void;
  setLists: (lists: TaskList[]) => void;
  setCurrentView: (view: AppView) => void;
  setSelectedTaskIds: (ids: string[]) => void;
  toggleTaskSelection: (id: string) => void;
  clearSelection: () => void;
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

// Create a Zustand store
const store = create<AppStore>()((set) => ({
  tasks: [],
  lists: [],
  currentView: { kind: 'system', view: 'inbox' },
  selectedTaskIds: [],
  isOnline: navigator.onLine !== undefined ? navigator.onLine : true,
  isSyncing: false,
  showCompleted: false,
  sortBy: 'createdAt',
  sortAsc: false,
  syncState: 'idle',
  session: null,
  rejections: [],
  snapshotLoaded: null,
  updateReady: false,

  setTasks: (tasks) => set({ tasks }),
  setLists: (lists) => set({ lists }),
  setCurrentView: (view) =>
    set({ currentView: view, selectedTaskIds: [] }),
  setSelectedTaskIds: (ids) => set({ selectedTaskIds: ids }),
  toggleTaskSelection: (id) =>
    set((state) => {
      const exists = state.selectedTaskIds.includes(id);
      return {
        selectedTaskIds: exists
          ? state.selectedTaskIds.filter((tid) => tid !== id)
          : [...state.selectedTaskIds, id],
      };
    }),
  clearSelection: () => set({ selectedTaskIds: [] }),
  setIsOnline: (online) => set({ isOnline: online }),
  setIsSyncing: (syncing) => set({ isSyncing: syncing }),
  setShowCompleted: (show) => set({ showCompleted: show }),
  setSortBy: (field) => set({ sortBy: field }),
  toggleSortAsc: () =>
    set((state) => ({ sortAsc: !state.sortAsc })),
  setSyncState: (syncState) => set({ syncState }),
  setSession: (session) => set({ session }),
  setRejections: (rejections) => set({ rejections }),
  setSnapshotLoaded: (snapshotLoaded) => set({ snapshotLoaded }),
  setUpdateReady: (updateReady) => set({ updateReady }),
}));

export default store;
export type AppStoreType = typeof store;