// Taskly Web — Smart Add Parser (stub)
// Replaces KMP module until real integration is available.
// Token syntax: ^dueDate ~startDate !priority #listOrTag *repeat @location =estimate +contact //note

import type { SmartAddToken, SmartAddResult, RepeatMode } from '../../types';

// Date parsing helpers
const DAYS_RU: Record<string, number> = {
  сегодня: 0,
  завтра: 1,
  послезавтра: 2,
  понедельник: 1,
  вторник: 2,
  среда: 3,
  четверг: 4,
  пятница: 5,
  суббота: 6,
  воскресенье: 0,
  пн: 1,
  вт: 2,
  ср: 3,
  чт: 4,
  пт: 5,
  сб: 6,
  вс: 0,
};

const DAYS_EN: Record<string, number> = {
  today: 0,
  tomorrow: 1,
  'day after tomorrow': 2,
  monday: 1,
  tuesday: 2,
  wednesday: 3,
  thursday: 4,
  friday: 5,
  saturday: 6,
  sunday: 0,
  mon: 1,
  tue: 2,
  wed: 3,
  thu: 4,
  fri: 5,
  sat: 6,
  sun: 0,
};

const MONTHS_RU: Record<string, number> = {
  янв: 0, фев: 1, мар: 2, апр: 3, май: 4, июн: 5, июл: 6,
  авг: 7, сен: 8, окт: 9, ноя: 10, дек: 11,
  января: 0, февраля: 1, марта: 2, апреля: 3, мая: 4, июня: 5,
  июля: 6, августа: 7, сентября: 8, октября: 9, ноября: 10, декабря: 11,
};

// Characters that start a token
const TOKEN_PREFIXES = new Set(['^', '~', '!', '#', '*', '@', '=', '+']);

/**
 * Tokenize the input string.
 * Splits into tokens (prefix-word) and plain text.
 */
function tokenize(input: string): Array<{ raw: string; start: number; end: number }> {
  const tokens: Array<{ raw: string; start: number; end: number }> = [];
  const words = input.split(' ');
  let charIndex = 0; // tracks position in original string

  for (let i = 0; i < words.length; i++) {
    const word = words[i];
    if (word.length === 0) {
      charIndex += 1; // skip space
      continue;
    }

    const firstChar = word[0];

    if (firstChar === '/' && word.length >= 2 && word[1] === '/') {
      // Note token: // rest of the input (from this word to end)
      const noteRaw = words.slice(i).join(' ');
      tokens.push({
        raw: noteRaw,
        start: charIndex,
        end: charIndex + noteRaw.length,
      });
      charIndex += word.length + 1;
      continue;
    }

    if (TOKEN_PREFIXES.has(firstChar)) {
      let raw = word;
      // For tokens that may have multi-word values (^, ~, *, =),
      // greedily consume following words that don't start with a prefix
      if (firstChar === '^' || firstChar === '~' || firstChar === '*' || firstChar === '=') {
        let j = i + 1;
        while (j < words.length) {
          const nw = words[j];
          if (TOKEN_PREFIXES.has(nw[0]) || (nw[0] === '/' && nw.length >= 2 && nw[1] === '/')) {
            break;
          }
          raw += ' ' + nw;
          j++;
        }
        tokens.push({
          raw,
          start: charIndex,
          end: charIndex + raw.length,
        });
        // Advance past all consumed words and their spaces
        let consumedLen = word.length;
        for (let k = i + 1; k < j; k++) {
          consumedLen += 1 + words[k].length; // space + word
        }
        charIndex += consumedLen + 1;
        i = j - 1; // for loop will i++
        continue;
      }

      // For single-word tokens (#, !, @, +)
      tokens.push({
        raw,
        start: charIndex,
        end: charIndex + word.length,
      });
      charIndex += word.length + 1;
    } else {
      charIndex += word.length + 1;
    }
  }

  return tokens;
}

function parseDateToken(raw: string): string | undefined {
  const value = raw.replace(/^[\^~]/, '').trim().toLowerCase();
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());

  // Check known day names
  const checkDayNames = (dict: Record<string, number>): string | undefined => {
    if (dict[value] !== undefined) {
      const target = dict[value];
      const dayOfWeek = now.getDay();
      // For today/tomorrow use fixed offsets
      if (dict === DAYS_RU || dict === DAYS_EN) {
        if (value === 'сегодня' || value === 'today') {
          return today.toISOString();
        }
        if (value === 'завтра' || value === 'tomorrow') {
          const d = new Date(today.getTime() + 86400000);
          return d.toISOString();
        }
        if (value === 'послезавтра' || value === 'day after tomorrow') {
          const d = new Date(today.getTime() + 2 * 86400000);
          return d.toISOString();
        }
      }
      let diff = target - dayOfWeek;
      if (diff <= 0) diff += 7;
      const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() + diff);
      return d.toISOString();
    }
    return undefined;
  };

  const ru = checkDayNames(DAYS_RU);
  if (ru) return ru;
  const en = checkDayNames(DAYS_EN);
  if (en) return en;

  // "через N дней"
  const throughRu = value.match(/^через\s+(\d+)/);
  if (throughRu) {
    const days = parseInt(throughRu[1], 10);
    const d = new Date(today.getTime() + days * 86400000);
    return d.toISOString();
  }

  // "in N days"
  const throughEn = value.match(/^in\s+(\d+)/);
  if (throughEn) {
    const days = parseInt(throughEn[1], 10);
    const d = new Date(today.getTime() + days * 86400000);
    return d.toISOString();
  }

  // DD month
  const dateRu = value.match(/^(\d{1,2})\s*(янв|фев|мар|апр|май|июн|июл|авг|сен|окт|ноя|дек)/i);
  if (dateRu) {
    const day = parseInt(dateRu[1], 10);
    const month = MONTHS_RU[dateRu[2].toLowerCase()] || 0;
    const year = today.getFullYear();
    const d = new Date(year, month, day);
    if (d < today) d.setFullYear(year + 1);
    return d.toISOString();
  }

  // DD.MM.YYYY or DD/MM/YYYY
  const dateNum = value.match(/^(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))?$/);
  if (dateNum) {
    const day = parseInt(dateNum[1], 10);
    const month = parseInt(dateNum[2], 10) - 1;
    let year = parseInt(dateNum[3] || `${today.getFullYear()}`, 10);
    if (dateNum[3] && dateNum[3].length === 2) year += 2000;
    return new Date(year, month, day).toISOString();
  }

  // HH:MM
  const timeOnly = value.match(/^(\d{1,2}):(\d{2})$/);
  if (timeOnly) {
    const d = new Date();
    d.setHours(parseInt(timeOnly[1], 10), parseInt(timeOnly[2], 10), 0, 0);
    return d.toISOString();
  }

  return undefined;
}

function parseTimeEstimate(raw: string): number | undefined {
  const value = raw.replace(/^=/, '').trim().toLowerCase();
  const minMatch = value.match(/^(\d+)\s*(мин|minutes?|mins?|min)?$/);
  if (minMatch) return parseInt(minMatch[1], 10);
  const hourMatch = value.match(/^(\d+)\s*(ч|h|hours?)?$/);
  if (hourMatch) return parseInt(hourMatch[1], 10) * 60;
  return undefined;
}

function parseRepeatRule(raw: string): { rule?: string; mode?: RepeatMode } | undefined {
  const value = raw.replace(/^\*/, '').trim().toLowerCase();

  // "каждую неделю", "каждый день"
  const everyRu = value.match(/^кажд(?:ую|ый)\s+(.+)$/);
  if (everyRu) {
    const freqMap: Record<string, string> = {
      день: 'DAILY', дня: 'DAILY', дней: 'DAILY',
      неделю: 'WEEKLY', недели: 'WEEKLY', недель: 'WEEKLY',
      месяц: 'MONTHLY', месяца: 'MONTHLY',
      год: 'YEARLY', года: 'YEARLY',
    };
    const freq = freqMap[everyRu[1]] || 'DAILY';
    return { rule: `FREQ=${freq}`, mode: 'every' };
  }

  // "every day", "every week"
  const everyEn = value.match(/^every\s+(.+)$/);
  if (everyEn) {
    const freqMap: Record<string, string> = {
      day: 'DAILY', days: 'DAILY',
      week: 'WEEKLY', weeks: 'WEEKLY',
      month: 'MONTHLY', months: 'MONTHLY',
      year: 'YEARLY', years: 'YEARLY',
    };
    const freq = freqMap[everyEn[1]] || 'DAILY';
    return { rule: `FREQ=${freq}`, mode: 'every' };
  }

  // "after 2 weeks"
  const afterMatch = value.match(/^after\s+(\d+)\s*(day|days|week|weeks)?$/);
  if (afterMatch) {
    const interval = parseInt(afterMatch[1], 10);
    const unit = afterMatch[2] || 'days';
    const freq = unit.startsWith('week') ? 'WEEKLY' : 'DAILY';
    return { rule: `FREQ=${freq};INTERVAL=${interval}`, mode: 'after' };
  }

  return undefined;
}

export function parseSmartAdd(input: string): SmartAddResult {
  const rawTokens = tokenize(input);
  const tokens: SmartAddToken[] = [];
  let name = '';
  let dueDate: string | undefined;
  let startDate: string | undefined;
  let priority: number | undefined;
  let listName: string | undefined;
  const tags: string[] = [];
  let repeatRule: string | undefined;
  let repeatMode: RepeatMode | undefined;
  let location: string | undefined;
  let estimateMin: number | undefined;
  let note: string | undefined;

  // Separate token and non-token parts to build name
  const nameParts: string[] = [];
  let pos = 0;

  for (const rt of rawTokens) {
    // Text before this token
    if (rt.start > pos) {
      nameParts.push(input.substring(pos, rt.start).trim());
    }

    const ch = rt.raw[0];
    const value = rt.raw.substring(1).trim();

    switch (ch) {
      case '^': {
        tokens.push({ type: 'dueDate', raw: rt.raw, value, start: rt.start, end: rt.end });
        const parsed = parseDateToken(rt.raw);
        if (parsed) dueDate = parsed;
        break;
      }
      case '~': {
        tokens.push({ type: 'startDate', raw: rt.raw, value, start: rt.start, end: rt.end });
        const parsed = parseDateToken(rt.raw);
        if (parsed) startDate = parsed;
        break;
      }
      case '!': {
        const p = parseInt(value, 10);
        if (p >= 1 && p <= 3) priority = p;
        tokens.push({ type: 'priority', raw: rt.raw, value, start: rt.start, end: rt.end });
        break;
      }
      case '#': {
        tokens.push({ type: 'list', raw: rt.raw, value, start: rt.start, end: rt.end });
        if (!listName) listName = value;
        else tags.push(value);
        break;
      }
      case '*': {
        tokens.push({ type: 'repeat', raw: rt.raw, value, start: rt.start, end: rt.end });
        const parsed = parseRepeatRule(rt.raw);
        if (parsed) {
          repeatRule = parsed.rule;
          repeatMode = parsed.mode;
        }
        break;
      }
      case '@': {
        tokens.push({ type: 'location', raw: rt.raw, value, start: rt.start, end: rt.end });
        location = value;
        break;
      }
      case '=': {
        tokens.push({ type: 'estimate', raw: rt.raw, value, start: rt.start, end: rt.end });
        estimateMin = parseTimeEstimate(rt.raw);
        break;
      }
      case '+': {
        tokens.push({ type: 'contact', raw: rt.raw, value, start: rt.start, end: rt.end });
        break;
      }
      default:
        // Note (//) handled separately
        if (rt.raw.startsWith('//')) {
          tokens.push({ type: 'note', raw: rt.raw, value: rt.raw.substring(2).trim(), start: rt.start, end: rt.end });
          note = rt.raw.substring(2).trim();
        }
        break;
    }

    pos = rt.end;
  }

  // Remaining text after last token
  if (pos < input.length) {
    nameParts.push(input.substring(pos).trim());
  }

  name = nameParts.filter((p) => p.length > 0).join(' ').trim();

  return {
    name,
    tokens,
    dueDate,
    startDate,
    priority,
    listName,
    tags,
    repeatRule,
    repeatMode,
    location,
    estimateMin,
    note,
  };
}