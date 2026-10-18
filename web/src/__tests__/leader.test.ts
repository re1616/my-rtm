// WEB-S5: one sync leader per browser; handover when the leader tab goes away.
// Uses a lock manager with the real Web Locks semantics (FIFO, held while the callback's
// promise is pending, abortable while queued) and Node's real BroadcastChannel.
import { describe, it, expect, afterEach } from 'vitest';
import { BroadcastChannel as NodeBroadcastChannel } from 'node:worker_threads';
import { SyncLeadership, CHANNEL_NAME, type LockManagerLike, type ChannelLike, type SyncMessage } from '../features/sync/leader';

class WebLocks implements LockManagerLike {
  private held = false;
  private queue: Array<() => void> = [];

  async request(_name: string, options: { signal?: AbortSignal }, callback: (lock: unknown) => Promise<void>): Promise<unknown> {
    if (this.held) {
      await new Promise<void>((resolve, reject) => {
        const entry = () => resolve();
        this.queue.push(entry);
        options.signal?.addEventListener('abort', () => {
          this.queue = this.queue.filter((e) => e !== entry);
          reject(new Error('AbortError'));
        });
      });
    }
    this.held = true;
    try {
      return await callback({});
    } finally {
      this.held = false;
      this.queue.shift()?.();
    }
  }
}

const tabs: SyncLeadership[] = [];
function tab(locks: LockManagerLike, channel: ChannelLike | null = null) {
  const t = new SyncLeadership({ locks, channel });
  tabs.push(t);
  return t;
}
const flush = () => new Promise((r) => setTimeout(r, 0));

afterEach(() => {
  tabs.splice(0).forEach((t) => t.close());
});

describe('WEB-S5: leader election', () => {
  it('only one of several tabs leads', async () => {
    const locks = new WebLocks();
    const led: string[] = [];
    const [a, b, c] = [tab(locks), tab(locks), tab(locks)];
    a.start(() => { led.push('a'); return () => {}; });
    b.start(() => { led.push('b'); return () => {}; });
    c.start(() => { led.push('c'); return () => {}; });
    await flush();
    expect(led).toEqual(['a']);
    expect([a.isLeader, b.isLeader, c.isLeader]).toEqual([true, false, false]);
  });

  it('when the leader goes away the next tab takes over within 2 s and the old loop stops', async () => {
    const locks = new WebLocks();
    let aStopped = false;
    const [a, b] = [tab(locks), tab(locks)];
    a.start(() => () => { aStopped = true; });
    let bLedAt = 0;
    b.start(() => { bLedAt = Date.now(); return () => {}; });
    await flush();
    expect(b.isLeader).toBe(false);

    const closedAt = Date.now();
    a.stop(); // tab closed
    await flush();
    expect(aStopped).toBe(true);
    expect(a.isLeader).toBe(false);
    expect(b.isLeader).toBe(true);
    expect(bLedAt - closedAt).toBeLessThan(2000);
  });

  it('a tab that leaves while waiting never leads', async () => {
    const locks = new WebLocks();
    const [a, b, c] = [tab(locks), tab(locks), tab(locks)];
    const led: string[] = [];
    a.start(() => { led.push('a'); return () => {}; });
    b.start(() => { led.push('b'); return () => {}; });
    c.start(() => { led.push('c'); return () => {}; });
    await flush();
    b.stop(); // closed while queued
    a.stop();
    await flush();
    expect(led).toEqual(['a', 'c']);
    expect(c.isLeader).toBe(true);
  });

  it('without the Web Locks API the tab leads on its own', () => {
    const t = tab(null as unknown as LockManagerLike);
    let led = false;
    t.start(() => { led = true; return () => {}; });
    expect(led).toBe(true);
    expect(t.isLeader).toBe(true);
  });
});

describe('WEB-S5: messages between tabs', () => {
  it('reach the other tabs but not the sender', async () => {
    const mk = () => new NodeBroadcastChannel(CHANNEL_NAME) as unknown as ChannelLike;
    const locks = new WebLocks();
    const leader = tab(locks, mk());
    const follower = tab(locks, mk());
    const atLeader: SyncMessage[] = [];
    const atFollower: SyncMessage[] = [];
    leader.onMessage((m) => atLeader.push(m));
    follower.onMessage((m) => atFollower.push(m));

    follower.post({ type: 'local-change' });
    leader.post({ type: 'data-changed' });
    await new Promise((r) => setTimeout(r, 50));

    expect(atLeader).toEqual([{ type: 'local-change' }]);
    expect(atFollower).toEqual([{ type: 'data-changed' }]);
  });
});
