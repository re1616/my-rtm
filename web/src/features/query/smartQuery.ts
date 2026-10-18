// Taskly Web — smart lists and search over local tasks (WEB-F5).
//
// The query language is evaluated by the shared Kotlin module — the same predicate the server
// compiles to SQL and Android runs over Room (QA-3) — so a smart list shows the same tasks on
// every client.
import { filterTasks as kmpFilterTasks, queryError as kmpQueryError } from '@taskly/shared';
import type { Task, TaskList, TaskLocation } from '../../types';
import { offsetString } from '../smartadd/parser';
import { toWireTimestamp } from '../../entities/taskRepository';

export interface QueryProblem {
  message: string;
  /** Character offset of the problem in the query, when the parser knows it. */
  position?: number;
}

/** null when the query is valid. */
export function checkQuery(query: string): QueryProblem | null {
  const raw = kmpQueryError(query);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as QueryProblem;
  } catch {
    return { message: String(raw) };
  }
}

export interface QueryContext {
  lists: TaskList[];
  locations: TaskLocation[];
  now?: Date;
  timeZone?: string;
}

/**
 * Tasks matching [query], in input order. Throws when the query is invalid
 * (callers validate with checkQuery first and show the problem instead).
 */
export function runQuery(query: string, tasks: Task[], ctx: QueryContext): Task[] {
  const now = ctx.now ?? new Date();
  const timeZone = ctx.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC';
  const wire = tasks.map((t) => ({
    id: t.id,
    name: t.name,
    listId: t.listId,
    parentId: t.parentId,
    locationId: t.locationId,
    priority: t.priority,
    dueAt: toWireTimestamp(t.dueDate),
    dueHasTime: t.dueHasTime,
    startAt: toWireTimestamp(t.startDate),
    estimateMin: t.estimateMin,
    rrule: t.repeatRule,
    completedAt: t.completedAt,
    postponedCount: t.postponedCount ?? 0,
    tags: t.tags,
    createdAt: t.createdAt,
    updatedAt: t.updatedAt,
  }));
  const context = {
    lists: ctx.lists.map((l) => ({ id: l.id, name: l.id === 'sys-inbox' ? 'Inbox' : l.name })),
    locations: ctx.locations.map((l) => ({ id: l.id, name: l.name })),
  };
  const out = JSON.parse(
    kmpFilterTasks(query, JSON.stringify(wire), now.getTime(), timeZone, offsetString(now), JSON.stringify(context)),
  ) as { ids?: string[]; error?: string };
  if (out.error) throw new Error(out.error);
  const ids = new Set(out.ids ?? []);
  return tasks.filter((t) => ids.has(t.id));
}
