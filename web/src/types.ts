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
  /** @deprecated legacy single note (before v6); notes live in the `notes` table now. */
  notes?: string;
  parentId?: string;
  locationId?: string;
  deletedAt?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

// ── Note (markdown, several per task) ──
export interface TaskNote {
  id: string;
  taskId: string;
  content: string;
  createdAt: string;
  updatedAt: string;
}

// ── Attachment (link to an external file) ──
export interface TaskAttachment {
  id: string;
  taskId: string;
  name: string;
  url: string;
  createdAt: string;
}

/** One entry of the task card history (server change log). */
export interface TaskHistoryEntry {
  seq: number;
  entity: string;
  entityId: string;
  op: string;
  payload: string;
  ts: string;
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
  dueHasTime?: boolean;
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
  | 'tag_remove'
  | 'restore';

export type SyncEntity = 'task' | 'list' | 'tag' | 'location' | 'note' | 'attachment';

export interface SyncOperation {
  opId: string;
  /** Monotonic queue position: ops are sent strictly in this order. */
  seq: number;
  /** Filled in at send time from the session (`user:<id>`); empty while queued offline. */
  scope: string;
  entity: SyncEntity;
  entityId: string;
  type: SyncOpType;
  /** Changed fields only, in the server's wire format (camelCase, null = clear). */
  fields: Record<string, unknown>;
  baseVersion?: number;
  clientTs: string;
  /** pending → sending → (removed when the server acknowledged it) | failed (rejected, retried later). */
  status: 'pending' | 'sending' | 'failed';
  retryCount: number;
  lastError?: string;
}

/** Authenticated server session stored locally. */
export interface Session {
  userId: string;
  accessToken: string;
  refreshToken: string;
  email?: string;
}

/** Server-side location entity, synced via SYNC-6. */
export interface TaskLocation {
  id: string;
  name: string;
  color?: string;
  lat?: number;
  lng?: number;
  radius?: number;
  createdAt: string;
  updatedAt?: string;
}

// ── System view types ──
export type SystemView = 'inbox' | 'today' | 'tomorrow' | 'overdue' | 'week' | 'all' | 'completed';

// ── App View ──
export type AppView =
  | { kind: 'system'; view: SystemView }
  | { kind: 'list'; listId: string }
  | { kind: 'smart'; listId: string }
  | { kind: 'tag'; tag: string }
  | { kind: 'location'; locationId: string }
  | { kind: 'search'; query: string }
  | { kind: 'task-detail'; taskId: string }
  | { kind: 'settings' }
  | { kind: 'onboarding' }
  | { kind: 'smart-lists' }
  | { kind: 'help' };

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