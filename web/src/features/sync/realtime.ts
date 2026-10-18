// Taskly Web — realtime change notifications over the server WebSocket (WEB-S5).
//
// The server pushes `sync_update` after every committed change in the user's scope; the leader
// tab then pulls the delta. The socket carries signals only — data always comes through
// /v1/sync/changes, so a missed message just means the next scheduled pull catches up.
import { backoffDelay } from './backoff';

const SIGNED_OUT_POLL_MS = 2_000;

export interface SocketLike {
  send(data: string): void;
  close(): void;
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: unknown) => void) | null;
  onerror: ((ev: unknown) => void) | null;
}

export interface RealtimeOptions {
  url: string;
  /** Current access token, or null when signed out (no connection then). */
  getToken: () => Promise<string | null>;
  /** Called for every change notification. */
  onUpdate: () => void;
  createSocket?: (url: string) => SocketLike;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/** ws(s)://… for the configured API origin, or the page's own origin (Vite proxy in dev). */
export function realtimeUrl(apiBase: string, location: { protocol: string; host: string }): string {
  if (apiBase) return apiBase.replace(/^http/, 'ws').replace(/\/$/, '') + '/v1/ws';
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/v1/ws`;
}

/** Keeps one authenticated socket open, reconnecting with backoff. Returns a closer. */
export function connectRealtime(opts: RealtimeOptions): { close(): void } {
  const create = opts.createSocket ?? ((url: string) => new WebSocket(url) as unknown as SocketLike);
  const setTimer = opts.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = opts.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  let socket: SocketLike | null = null;
  let timer: unknown = null;
  let attempt = 0;
  let closed = false;

  const scheduleReconnect = () => {
    if (closed) return;
    timer = setTimer(() => void open(), backoffDelay(attempt++));
  };

  async function open(): Promise<void> {
    timer = null;
    if (closed) return;
    const token = await opts.getToken();
    if (!token) {
      // Signed out: look again soon (not backoff — the user may sign in any moment)
      if (!closed) timer = setTimer(() => void open(), SIGNED_OUT_POLL_MS);
      return;
    }
    let s: SocketLike;
    try {
      s = create(opts.url);
    } catch {
      scheduleReconnect();
      return;
    }
    socket = s;
    s.onopen = () => s.send(JSON.stringify({ type: 'auth', payload: JSON.stringify({ token }) }));
    s.onmessage = (ev) => {
      let msg: { type?: string } = {};
      try {
        msg = JSON.parse(String(ev.data)) as { type?: string };
      } catch {
        return;
      }
      if (msg.type === 'auth_ok') attempt = 0;
      else if (msg.type === 'sync_update') opts.onUpdate();
      else if (msg.type === 'error') s.close(); // e.g. expired token: reconnect with a fresh one
    };
    s.onerror = () => {
      /* onclose follows */
    };
    s.onclose = () => {
      if (socket === s) socket = null;
      scheduleReconnect();
    };
  }

  void open();
  return {
    close() {
      closed = true;
      if (timer != null) clearTimer(timer);
      socket?.close();
      socket = null;
    },
  };
}
