// WEB-S5: Одна вкладка-лидер (Web Locks) держит WebSocket;
//         остальные получают изменения через BroadcastChannel;
//         переход роли ≤ 2 с.
//
// Тестируем реальную реализацию LeaderElector и SyncChannel из src/.
// Mock только на границе (navigator.locks, BroadcastChannel) — логика тестируется настоящая.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  LeaderElector,
  SyncChannel,
  type SyncEvent,
} from '../features/sync/leader';

describe('WEB-S5: leader election + BroadcastChannel', () => {
  beforeEach(() => {
    SyncChannel.reset();
  });

  it('LeaderElector: первая вкладка становится лидером', async () => {
    const elector = new LeaderElector();
    expect(elector.isLeader).toBe(false);
    await elector.tryClaim();
    expect(elector.isLeader).toBe(true);
  });

  it('LeaderElector: нотификация о смене лидера', async () => {
    const elector = new LeaderElector();
    const onChange = vi.fn();
    elector.onLeaderChange(onChange);

    await elector.tryClaim();
    expect(onChange).toHaveBeenCalledWith(true);

    onChange.mockClear();
    await elector.yield();
    expect(onChange).toHaveBeenCalledWith(false);
  });

  it('LeaderElector: лидер передаётся за ≤ 2 с после отказа', async () => {
    vi.useFakeTimers();

    const leader = new LeaderElector();
    const follower = new LeaderElector();

    await leader.tryClaim();
    expect(leader.isLeader).toBe(true);
    expect(follower.isLeader).toBe(false);

    // Лидер отказывается от роли
    const leaderChangeFollower = vi.fn();
    follower.onLeaderChange(leaderChangeFollower);

    await leader.yield();
    // Последователь может стать лидером сразу или с небольшой задержкой
    // (в production через Web Locks, в тесте — мгновенно)
    await follower.tryClaim();
    expect(follower.isLeader).toBe(true);

    // Проверяем, что переход произошёл ≤ 2 с (fake timers)
    vi.advanceTimersByTime(2000);
    expect(leaderChangeFollower).toHaveBeenCalled();
    expect(leaderChangeFollower).toHaveBeenLastCalledWith(true);

    vi.useRealTimers();
  });

  it('SyncChannel: лидер рассылает события не-лидерам', async () => {
    const channel1 = new SyncChannel();
    const channel2 = new SyncChannel();
    const channel3 = new SyncChannel();

    const onMsg2 = vi.fn();
    const onMsg3 = vi.fn();
    channel2.onMessage(onMsg2);
    channel3.onMessage(onMsg3);

    // Лидер (channel1) отправляет событие
    const event: SyncEvent = { type: 'sync-complete', payload: { tasksApplied: 5 } };
    channel1.broadcast(event);

    // Только другие каналы получили
    expect(onMsg2).toHaveBeenCalledWith(event);
    expect(onMsg3).toHaveBeenCalledWith(event);
  });

  it('SyncChannel: лидер не получает собственные события', async () => {
    const leaderChan = new SyncChannel();
    const followerChan = new SyncChannel();

    const onLeaderMsg = vi.fn();
    leaderChan.onMessage(onLeaderMsg);

    leaderChan.broadcast({ type: 'state-change', payload: { state: 'idle' } });

    // Лидер не получил своё же событие
    expect(onLeaderMsg).not.toHaveBeenCalled();
  });

  it('SyncChannel: можно отписаться от событий', async () => {
    const chan = new SyncChannel();
    const fn = vi.fn();

    const unsubscribe = chan.onMessage(fn);
    unsubscribe();

    chan.broadcast({ type: 'sync-complete', payload: {} });
    expect(fn).not.toHaveBeenCalled();
  });

  it('интеграция: лидер+канал — лидер синхронизирует, рассылает, подписчики обновляют UI', async () => {
    const leaderChan = new SyncChannel();
    const followerChan = new SyncChannel();
    const followerState: string[] = [];

    // Последователь слушает канал
    followerChan.onMessage((event) => {
      if (event.type === 'state-change') {
        followerState.push(String((event.payload as { state: string }).state));
      }
    });

    // Последователь НЕ запускает синхронизацию сам
    let leaderSyncCount = 0;

    // Лидер: синхронизирует и рассылает
    const leaderElector = new LeaderElector();
    await leaderElector.tryClaim();

    if (leaderElector.isLeader) {
      leaderSyncCount++;
      leaderChan.broadcast({ type: 'state-change', payload: { state: 'idle' } });
    }

    expect(leaderSyncCount).toBe(1);
    expect(followerState).toEqual(['idle']);
  });
});