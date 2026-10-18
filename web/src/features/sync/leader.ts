// Taskly Web — one sync leader per browser (WEB-S5).
//
// Exactly one tab runs the sync loop and holds the WebSocket. Leadership is a Web Lock: the
// leader's lock callback never resolves while it leads, other tabs queue on the same lock and
// the browser hands it to the next one as soon as the leader tab closes or crashes (immediately,
// well under the 2 s budget). Tabs talk over a BroadcastChannel: followers ask the leader to
// sync after local edits, the leader tells everyone when data changed.

/** The part of `navigator.locks` we use (Web Locks API). */
export interface LockManagerLike {
  request(name: string, options: { signal?: AbortSignal }, callback: (lock: unknown) => Promise<void>): Promise<unknown>;
}

/** The part of `BroadcastChannel` we use. */
export interface ChannelLike {
  postMessage(message: unknown): void;
  addEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  removeEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  close(): void;
}

export type SyncMessage =
  /** A follower wrote to the queue: the leader should push now. */
  | { type: 'local-change' }
  /** The leader applied server data: everyone reloads views from IndexedDB. */
  | { type: 'data-changed' }
  /** The leader's sync state, so every tab shows the same indicator. */
  | { type: 'state'; state: string };

export const LOCK_NAME = 'taskly-sync-leader';
export const CHANNEL_NAME = 'taskly-sync';

export interface LeadershipDeps {
  locks?: LockManagerLike | null;
  channel?: ChannelLike | null;
}

function platformLocks(): LockManagerLike | null {
  const nav = typeof navigator !== 'undefined' ? (navigator as Navigator & { locks?: LockManagerLike }) : undefined;
  return nav?.locks && typeof nav.locks.request === 'function' ? nav.locks : null;
}

function platformChannel(): ChannelLike | null {
  return typeof BroadcastChannel !== 'undefined' ? (new BroadcastChannel(CHANNEL_NAME) as unknown as ChannelLike) : null;
}

/**
 * Elects this tab as sync leader when possible and runs [onLead] while it leads.
 * [onLead] returns a cleanup function, called when leadership ends (stop()).
 */
export class SyncLeadership {
  private readonly locks: LockManagerLike | null;
  private readonly channel: ChannelLike | null;
  private abort: AbortController | null = null;
  private releaseLock: (() => void) | null = null;
  private _isLeader = false;
  private readonly handlers = new Set<(m: SyncMessage) => void>();
  private readonly onChannelMessage = (event: MessageEvent) => {
    for (const h of this.handlers) h(event.data as SyncMessage);
  };

  constructor(deps: LeadershipDeps = {}) {
    this.locks = deps.locks === undefined ? platformLocks() : deps.locks;
    this.channel = deps.channel === undefined ? platformChannel() : deps.channel;
    this.channel?.addEventListener('message', this.onChannelMessage);
  }

  get isLeader(): boolean {
    return this._isLeader;
  }

  /** Queues for leadership; resolves immediately (leadership may come later). */
  start(onLead: () => () => void): void {
    if (this.abort) return;
    this.abort = new AbortController();
    if (!this.locks) {
      // No Web Locks (very old browser, tests): this tab is the only coordinator.
      this.lead(onLead);
      return;
    }
    this.locks
      .request(LOCK_NAME, { signal: this.abort.signal }, () => this.lead(onLead))
      .catch(() => {
        /* aborted by stop() before we got the lock */
      });
  }

  /** Gives up leadership (or the place in the queue). The next tab takes over at once. */
  stop(): void {
    this.abort?.abort();
    this.abort = null;
    this.releaseLock?.();
  }

  /** Sends to all OTHER tabs (BroadcastChannel never echoes to the sender). */
  post(message: SyncMessage): void {
    this.channel?.postMessage(message);
  }

  onMessage(handler: (m: SyncMessage) => void): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  close(): void {
    this.stop();
    this.channel?.removeEventListener('message', this.onChannelMessage);
    this.channel?.close();
  }

  /** Lock callback: lead until released. The lock is held while this promise is pending. */
  private lead(onLead: () => () => void): Promise<void> {
    this._isLeader = true;
    const cleanup = onLead();
    return new Promise<void>((resolve) => {
      this.releaseLock = () => {
        this.releaseLock = null;
        this._isLeader = false;
        cleanup();
        resolve();
      };
    });
  }
}
