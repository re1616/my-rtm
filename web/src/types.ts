// Taskly Web — Core Types

// ── Priorities ──
export type Priority = 1 | 2 | 3 | null;

// ── Repeat modes ──
export type RepeatMode = 'every' | 'after';

// ── Task ──
export interface Task {
  id: string; // UUIDv7
  listId: string;
  name: string;
  priority: Priority;
  dueDate?: string; // ISO 8601
  dueHasTime: boolean;
  startDate?: string;
  estimateMin?: number;
  repeatRule?: string; // RRULE
  repeatMode?: RepeatMode;
  url?: string;
  completedAt?: string;
  postponedCount: number;
  tags: string[];
  notes?: string;
  deletedAt?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

// ── List ──
export interface TaskList {
  id: string;
  name: string;
  isSystem?: boolean; // true for system lists like Inbox, Today, Overdue
  isSmart?: boolean;
  filterQuery?: string;
  color?: string;
  sortOrder: number;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
}

// ── Tag ──
export interface TaskTag {
  id: string;
  name: string;
  color?: string;
  createdAt: string;
}

// ── Smart Add Token ──
export interface SmartAddToken {
  type:
    | 'text'
    | 'dueDate'
    | 'startDate'
    | 'priority'
    | 'list'
    | 'tag'
    | 'repeat'
    | 'location'
    | 'estimate'
    | 'contact'
    | 'note';
  raw: string;
  value: string;
  start: number; // position in input string
  end: number;
}

export interface SmartAddResult {
  name: string;
  tokens: SmartAddToken[];
  dueDate?: string;
  startDate?: string;
  priority?: number;
  listName?: string;
  tags: string[];
  repeatRule?: string;
  repeatMode?: RepeatMode;
  location?: string;
  estimateMin?: number;
  note?: string;
}

// ── Sync Operations ──
export type SyncOpType =
  | 'create'
  | 'update'
  | 'delete'
  | 'complete'
  | 'uncomplete'
  | 'move'
  | 'tag_add'
  | 'tag_remove';

export interface SyncOperation {
  opId: string;
  scope: string;
  entity: string;
  entityId: string;
  type: SyncOpType;
  fields: Record<string, unknown>;
  baseVersion: number;
  clientTs: string;
  status: 'pending' | 'sending' | 'sent' | 'failed';
  retryCount: number;
}

// ── System view types ──
export type SystemView = 'inbox' | 'today' | 'overdue' | 'week' | 'all' | 'completed';

// ── App View ──
export type AppView =
  | { kind: 'system'; view: SystemView }
  | { kind: 'list'; listId: string }
  | { kind: 'smart'; listId: string };

// ── UI State ──
export interface UIState {
  view: AppView;
  selectedTaskIds: string[];
  isOnline: boolean;
  isSyncing: boolean;
  showCompleted: boolean;
  sortBy: SortField;
  sortAsc: boolean;
}

export type SortField = 'priority' | 'dueDate' | 'name' | 'createdAt' | 'updatedAt';