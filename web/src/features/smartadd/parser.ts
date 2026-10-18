// Taskly Web — Smart Add adapter over the shared Kotlin Multiplatform parser.
//
// The parsing rules live in ONE place: shared/src/commonMain/.../smartadd (ARCH 3.1).
// The server and Android call the same code; here it is the Kotlin/JS build of the module
// (`./gradlew :shared:webLibrary`, imported as "@taskly/shared").
import { parseSmartAdd as kmpParseSmartAdd } from '@taskly/shared';
import type { RepeatMode, SmartAddResult, SmartAddToken } from '../../types';

interface KmpToken {
  type: string;
  start: number;
  end: number;
  value: string;
}

interface KmpSmartAdd {
  name: string;
  tokens: KmpToken[];
  dueInstant?: string;
  dueDate?: string;
  dueHasTime?: boolean;
  startInstant?: string;
  startDate?: string;
  priority?: number;
  listName?: string;
  tags?: string[];
  rrule?: string;
  repeatMode?: string;
  location?: string;
  estimateMin?: number;
  contact?: string;
  note?: string;
}

const TOKEN_TYPES: Record<string, SmartAddToken['type']> = {
  due: 'dueDate',
  start: 'startDate',
  priority: 'priority',
  list: 'list',
  tag: 'tag',
  repeat: 'repeat',
  location: 'location',
  estimate: 'estimate',
  contact: 'contact',
  note: 'note',
};

export interface SmartAddContext {
  /** Names of the user's lists: "#Name" resolves to a list before a tag (SPEC F-2). */
  listNames?: string[];
  tagNames?: string[];
  /** Pinned "now" (tests); defaults to the current time. */
  now?: Date;
  /** Pinned "today" as yyyy-MM-dd (tests); defaults to the local date of `now`. */
  today?: string;
  /** IANA zone; defaults to the browser's zone. */
  timeZone?: string;
}

/** Browser's current UTC offset as "UTC+03:00" (fallback when IANA zones are unavailable). */
export function offsetString(now: Date): string {
  const minutes = -now.getTimezoneOffset();
  const sign = minutes >= 0 ? '+' : '-';
  const abs = Math.abs(minutes);
  const hh = String(Math.floor(abs / 60)).padStart(2, '0');
  const mm = String(abs % 60).padStart(2, '0');
  return `UTC${sign}${hh}:${mm}`;
}

function localIsoDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function parseSmartAdd(input: string, ctx: SmartAddContext = {}): SmartAddResult {
  const now = ctx.now ?? new Date();
  const timeZone = ctx.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC';
  const raw = kmpParseSmartAdd(
    input,
    ctx.today ?? localIsoDate(now),
    timeZone,
    offsetString(now),
    ctx.listNames ?? [],
    ctx.tagNames ?? [],
  );
  const r = JSON.parse(raw) as KmpSmartAdd;

  return {
    name: r.name,
    tokens: r.tokens.map((t) => ({
      type: TOKEN_TYPES[t.type] ?? 'text',
      raw: input.slice(t.start, t.end),
      value: t.value,
      start: t.start,
      end: t.end,
    })),
    dueDate: r.dueInstant,
    dueHasTime: r.dueHasTime ?? false,
    startDate: r.startInstant,
    priority: r.priority,
    listName: r.listName,
    tags: r.tags ?? [],
    repeatRule: r.rrule,
    repeatMode: r.repeatMode as RepeatMode | undefined,
    location: r.location,
    estimateMin: r.estimateMin,
    note: r.note,
  };
}
