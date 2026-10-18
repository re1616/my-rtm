// Taskly Web — Zustand Store
import { create } from 'zustand';
import type { Task, TaskList, AppView, SortField, Session } from '../types';
import type { SyncState } from '../features/sync/SyncService';

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
}));

export default store;
export type AppStoreType = typeof store;