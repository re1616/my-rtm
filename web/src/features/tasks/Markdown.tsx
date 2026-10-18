// Taskly Web — markdown for notes (WEB-F3), rendered without HTML.
//
// Notes come from other devices and, later, other people (shared lists), so they are untrusted.
// The text is turned into React elements only — no innerHTML anywhere — so markup in a note is
// shown as text, and links are kept only for http(s) and mailto (no javascript:, data:, …).
import React from 'react';

const SAFE_LINK = /^(https?:\/\/|mailto:)/i;

export function safeHref(url: string): string | null {
  const trimmed = url.trim();
  if (!SAFE_LINK.test(trimmed)) return null;
  try {
    const u = new URL(trimmed);
    return u.protocol === 'http:' || u.protocol === 'https:' || u.protocol === 'mailto:' ? u.href : null;
  } catch {
    return null;
  }
}

// Inline: `code`, **bold**, *italic* / _italic_, [text](url), bare http(s) links
const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*\s][^*]*\*|_[^_\s][^_]*_)|(\[[^\]]+\]\([^)\s]+\))|(https?:\/\/[^\s<>()]+)/g;

function inline(text: string, keyPrefix: string): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  let last = 0;
  let i = 0;
  for (const m of text.matchAll(INLINE)) {
    const start = m.index ?? 0;
    if (start > last) out.push(text.slice(last, start));
    const token = m[0];
    const key = `${keyPrefix}-${i++}`;
    if (m[1]) {
      out.push(<code key={key}>{token.slice(1, -1)}</code>);
    } else if (m[2]) {
      out.push(<strong key={key}>{inline(token.slice(2, -2), key)}</strong>);
    } else if (m[3]) {
      out.push(<em key={key}>{inline(token.slice(1, -1), key)}</em>);
    } else if (m[4]) {
      const sep = token.indexOf('](');
      const label = token.slice(1, sep);
      const href = safeHref(token.slice(sep + 2, -1));
      out.push(
        href ? (
          <a key={key} href={href} target="_blank" rel="noopener noreferrer nofollow">
            {label}
          </a>
        ) : (
          token
        ),
      );
    } else {
      const href = safeHref(token);
      out.push(
        href ? (
          <a key={key} href={href} target="_blank" rel="noopener noreferrer nofollow">
            {token}
          </a>
        ) : (
          token
        ),
      );
    }
    last = start + token.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

/** Markdown subset: headings, lists, quotes, code blocks, paragraphs and the inline forms above. */
export const Markdown: React.FC<{ text: string; className?: string }> = ({ text, className }) => {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const blocks: React.ReactNode[] = [];
  let i = 0;
  let k = 0;
  while (i < lines.length) {
    const line = lines[i];
    const key = `b${k++}`;
    if (!line.trim()) {
      i++;
      continue;
    }
    if (line.startsWith('```')) {
      const body: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) body.push(lines[i++]);
      i++;
      blocks.push(
        <pre key={key}>
          <code>{body.join('\n')}</code>
        </pre>,
      );
      continue;
    }
    const h = /^(#{1,3})\s+(.*)$/.exec(line);
    if (h) {
      const Tag = (`h${h[1].length + 3}`) as 'h4' | 'h5' | 'h6';
      blocks.push(<Tag key={key}>{inline(h[2], key)}</Tag>);
      i++;
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*[-*]\s+/, ''));
      blocks.push(
        <ul key={key}>
          {items.map((it, j) => (
            <li key={j}>{inline(it, `${key}-${j}`)}</li>
          ))}
        </ul>,
      );
      continue;
    }
    if (/^\s*\d+[.)]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*\d+[.)]\s+/, ''));
      blocks.push(
        <ol key={key}>
          {items.map((it, j) => (
            <li key={j}>{inline(it, `${key}-${j}`)}</li>
          ))}
        </ol>,
      );
      continue;
    }
    if (line.startsWith('>')) {
      const body: string[] = [];
      while (i < lines.length && lines[i].startsWith('>')) body.push(lines[i++].replace(/^>\s?/, ''));
      blocks.push(<blockquote key={key}>{inline(body.join(' '), key)}</blockquote>);
      continue;
    }
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() && !/^(```|#{1,3}\s|\s*[-*]\s|\s*\d+[.)]\s|>)/.test(lines[i])) para.push(lines[i++]);
    if (para.length === 0) para.push(lines[i++]);
    blocks.push(
      <p key={key}>
        {para.flatMap((p, j) => (j === 0 ? inline(p, `${key}-${j}`) : [<br key={`${key}-br${j}`} />, ...inline(p, `${key}-${j}`)]))}
      </p>,
    );
  }
  return <div className={className ?? 'markdown'}>{blocks}</div>;
};

export default Markdown;
